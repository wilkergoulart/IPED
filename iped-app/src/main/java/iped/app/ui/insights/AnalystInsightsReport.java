package iped.app.ui.insights;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileNameExtensionFilter;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexableField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.app.ui.App;
import iped.app.ui.ExternalFileOpen;
import iped.app.ui.Messages;
import iped.data.IItemId;
import iped.engine.localization.CategoryLocalization;
import iped.engine.search.IPEDSearcher;
import iped.engine.search.MultiSearchResult;
import iped.parsers.whatsapp.MessageSequenceGap;
import iped.parsers.whatsapp.WhatsAppParser;
import iped.properties.BasicProps;
import iped.properties.ExtraProperties;
import iped.utils.DateUtil;
import iped.viewers.api.CancelableWorker;
import iped.viewers.util.ProgressDialog;

/**
 * Generates an HTML report with insights about the case data to help the
 * analyst: WhatsApp chats and messages statistics and areas with most
 * geolocated items.
 */
public class AnalystInsightsReport extends CancelableWorker<File, Void> implements PropertyChangeListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnalystInsightsReport.class);

    private static final Pattern FRAGMENT_SUFFIX = Pattern.compile("_\\d+$");

    private static final String CHATS_QUERY = BasicProps.CONTENTTYPE + ":\"" + WhatsAppParser.WHATSAPP_CHAT + "\"";

    // messages of any kind (text, attachments, shared contacts...), except calls,
    // which are not shown in the chat either
    private static final String MESSAGES_QUERY = ExtraProperties.USER_ACCOUNT_TYPE + ":\"" + WhatsAppParser.WHATSAPP
            + "\" AND NOT " + BasicProps.CONTENTTYPE + ":(\"" + WhatsAppParser.WHATSAPP_ACCOUNT + "\" OR \""
            + WhatsAppParser.WHATSAPP_CONTACT + "\" OR \"" + WhatsAppParser.WHATSAPP_CALL + "\")";

    private static final Set<String> CHAT_FIELDS = Set.of(BasicProps.NAME, BasicProps.PATH, BasicProps.PARENTID,
            "chatId", ExtraProperties.CONVERSATION_MESSAGE_COUNTER.getName(),
            ExtraProperties.CONVERSATION_STORED_MESSAGES_COUNT.getName(), ExtraProperties.CONVERSATION_SEQUENCE_GAPS);

    private static final Set<String> MESSAGE_FIELDS = Set.of(ExtraProperties.COMMUNICATION_FROM,
            ExtraProperties.MESSAGE_DATE.getName(), BasicProps.DELETED);

    private static final String GEO_QUERY = ExtraProperties.LOCATIONS.replace(":", "\\:") + ":*";

    // date of a geolocated item: message date, then creation date from metadata
    // (e.g. EXIF of photos), then file system creation date
    private static final String[] GEO_DATE_FIELDS = { ExtraProperties.MESSAGE_DATE.getName(),
            ExtraProperties.COMMON_META_PREFIX + "dcterms:created", BasicProps.CREATED };

    private static final Set<String> GEO_FIELDS = Set.of(ExtraProperties.LOCATIONS, BasicProps.CATEGORY,
            GEO_DATE_FIELDS[0], GEO_DATE_FIELDS[1], GEO_DATE_FIELDS[2]);

    static final double GEO_RADIUS = 30;
    static final int MAX_HOTSPOTS = 20;
    static final int MIN_HOTSPOT_RECORDS = 2;

    private ProgressDialog progressDialog;
    private File file;

    public void generate() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileFilter(new FileNameExtensionFilter("HTML", "html"));
        File moduleDir = App.get().appCase.getAtomicSourceBySourceId(0).getModuleDir();
        fileChooser.setCurrentDirectory(moduleDir.getParentFile());
        fileChooser.setSelectedFile(new File(Messages.getString("AnalystInsights.DefaultFileName")));

        if (fileChooser.showSaveDialog(App.get()) == JFileChooser.APPROVE_OPTION) {
            file = fileChooser.getSelectedFile();
            if (!file.getName().toLowerCase().endsWith(".html")) {
                file = new File(file.getAbsolutePath() + ".html");
            }
            this.addPropertyChangeListener(this);
            progressDialog = new ProgressDialog(App.get(), this, true);
            this.execute();
        }
    }

    @Override
    protected File doInBackground() throws Exception {
        WhatsAppInsights whatsApp = new WhatsAppInsights();

        MultiSearchResult chats = search(CHATS_QUERY);
        for (int i = 0; i < chats.getLength() && !isCancelled(); i++) {
            IItemId itemId = chats.getItem(i);
            Document doc = getDocument(itemId, CHAT_FIELDS);
            String name = doc.get(BasicProps.NAME);
            String path = doc.get(BasicProps.PATH);
            String database = path != null && path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : path;
            // long chats are split in fragments named "<chat name>_<n>"
            String chatName = name != null ? FRAGMENT_SUFFIX.matcher(name).replaceFirst("") : null;
            String key = itemId.getSourceId() + "/" + doc.get(BasicProps.PARENTID) + "/" + doc.get("chatId") + "/"
                    + chatName;
            whatsApp.addChat(key, chatName, database,
                    getInt(doc, ExtraProperties.CONVERSATION_MESSAGE_COUNTER.getName()),
                    getInt(doc, ExtraProperties.CONVERSATION_STORED_MESSAGES_COUNT.getName()),
                    parseGaps(doc.getValues(ExtraProperties.CONVERSATION_SEQUENCE_GAPS)));
        }

        MultiSearchResult messages = search(MESSAGES_QUERY);
        for (int i = 0; i < messages.getLength() && !isCancelled(); i++) {
            Document doc = getDocument(messages.getItem(i), MESSAGE_FIELDS);
            whatsApp.addMessage(doc.get(ExtraProperties.COMMUNICATION_FROM),
                    parseDate(doc.get(ExtraProperties.MESSAGE_DATE.getName())),
                    Boolean.parseBoolean(doc.get(BasicProps.DELETED)));
            if ((i + 1) % 10000 == 0) {
                firePropertyChange("note", null, Messages.getString("AnalystInsights.Reading", i + 1,
                        messages.getLength()));
            }
        }

        GeoHotspots geo = new GeoHotspots(GEO_RADIUS);
        MultiSearchResult geoItems = search(GEO_QUERY);
        for (int i = 0; i < geoItems.getLength() && !isCancelled(); i++) {
            IItemId itemId = geoItems.getItem(i);
            Document doc = getDocument(itemId, GEO_FIELDS);
            String category = doc.get(BasicProps.CATEGORY);
            if (category != null) {
                category = CategoryLocalization.getInstance().getLocalizedCategory(category.trim());
            }
            Date date = null;
            for (String field : GEO_DATE_FIELDS) {
                if ((date = parseDate(doc.get(field))) != null) {
                    break;
                }
            }
            String item = itemId.getSourceId() + "_" + itemId.getId();
            for (String location : doc.getValues(ExtraProperties.LOCATIONS)) {
                geo.add(location, item, category, date);
            }
            if ((i + 1) % 10000 == 0) {
                firePropertyChange("note", null,
                        Messages.getString("AnalystInsights.ReadingGeo", i + 1, geoItems.getLength()));
            }
        }

        if (isCancelled()) {
            return null;
        }
        Files.writeString(file.toPath(), toHtml(whatsApp, geo), StandardCharsets.UTF_8);
        return file;
    }

    private MultiSearchResult search(String query) throws Exception {
        IPEDSearcher searcher = new IPEDSearcher(App.get().appCase, query);
        searcher.setNoScoring(true);
        return searcher.multiSearch();
    }

    private Document getDocument(IItemId itemId, Set<String> fields) throws IOException {
        int luceneId = App.get().appCase.getLuceneId(itemId);
        return App.get().appCase.getSearcher().doc(luceneId, new HashSet<>(fields));
    }

    private static Integer getInt(Document doc, String field) {
        IndexableField f = doc.getField(field);
        if (f == null) {
            return null;
        }
        if (f.numericValue() != null) {
            return f.numericValue().intValue();
        }
        try {
            return Integer.valueOf(f.stringValue().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<MessageSequenceGap> parseGaps(String[] values) {
        List<MessageSequenceGap> gaps = new ArrayList<>();
        for (String value : values) {
            MessageSequenceGap gap = MessageSequenceGap.parse(value);
            if (gap != null) {
                gaps.add(gap);
            }
        }
        return gaps;
    }

    private static Date parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return DateUtil.stringToDate(value);
        } catch (Exception e) {
            return DateUtil.tryToParseDate(value);
        }
    }

    private static String toHtml(WhatsAppInsights whatsApp, GeoHotspots geo) {
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z");
        String title = WhatsAppInsights.escape(Messages.getString("AnalystInsights.Title"));
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"UTF-8\">\n<title>").append(title)
                .append("</title>\n<style>\n")
                .append("body{font-family:Arial,Helvetica,sans-serif;margin:24px;color:#222}\n")
                .append("h1{font-size:22px}h2{font-size:19px;border-bottom:1px solid #ccc;padding-bottom:4px}")
                .append("h3{font-size:16px;margin-top:24px}\n")
                .append("table{border-collapse:collapse;margin:8px 0}")
                .append("th,td{border:1px solid #ccc;padding:4px 8px;text-align:left;vertical-align:top}\n")
                .append("th{background:#f2f2f2}td.num{text-align:right}tr.peak td,tr.diff td{background:#fff4d6}\n")
                .append(".bar{display:inline-block;height:10px;background:#4a7bd0;vertical-align:middle}\n")
                .append("td.nowrap{white-space:nowrap}.help,.note{color:#555;font-size:13px}\n")
                .append("</style>\n</head>\n<body>\n<h1>").append(title).append("</h1>\n<p class=\"note\">")
                .append(WhatsAppInsights.escape(Messages.getString("AnalystInsights.GeneratedAt", df.format(new Date()))))
                .append("<br>").append(WhatsAppInsights.escape(Messages.getString("AnalystInsights.DatesInUtc")))
                .append("</p>\n");
        sb.append(whatsApp.toHtml(Messages::getString));
        sb.append(geo.toHtml(geo.find(MAX_HOTSPOTS, MIN_HOTSPOT_RECORDS), MAX_HOTSPOTS, Messages::getString));
        sb.append("</body>\n</html>\n");
        return sb.toString();
    }

    @Override
    protected void done() {
        progressDialog.close();
        if (isCancelled()) {
            return;
        }
        try {
            ExternalFileOpen.open(get());
        } catch (Exception e) {
            LOGGER.error("Error generating analyst insights report", e);
            JOptionPane.showMessageDialog(App.get(), Messages.getString("AnalystInsights.Error", e.toString()),
                    Messages.getString("AnalystInsights.Title"), JOptionPane.ERROR_MESSAGE);
        }
    }

    @Override
    public void propertyChange(PropertyChangeEvent evt) {
        if (progressDialog.isCanceled()) {
            this.cancel(true);
        } else if ("note".equals(evt.getPropertyName())) {
            progressDialog.setNote((String) evt.getNewValue());
        }
    }
}
