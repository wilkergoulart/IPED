package iped.app.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JToolBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

import org.apache.lucene.document.Document;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.data.IIPEDSource;
import iped.data.IItem;
import iped.data.IItemId;
import iped.data.IMultiBookmarks;
import iped.engine.data.IPEDSource;
import iped.engine.data.ItemId;
import iped.engine.task.index.IndexItem;
import iped.properties.ExtraProperties;
import iped.properties.MediaTypes;
import iped.utils.IconUtil;
import iped.viewers.HtmlLinkViewer;
import iped.viewers.IcePDFViewer;
import iped.viewers.MetadataViewer;
import iped.viewers.MultiViewer;
import iped.viewers.api.AbstractViewer;
import javafx.application.Platform;

/**
 * An independent window showing the preview of an item, which does not change
 * when other items are selected in the main window. It is closed by the user.
 */
public class PinnedPreviewWindow extends JFrame {

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = LoggerFactory.getLogger(PinnedPreviewWindow.class);

    private static final String resPath = '/' + App.class.getPackageName().replace('.', '/') + '/';

    /** above this number of items, the user must confirm opening the windows */
    static final int MAX_ITEMS_WITHOUT_CONFIRMATION = 5;

    private static final int CASCADE_OFFSET = 32;

    private static final String LOADING_CARD = "loading";
    private static final String PREVIEW_CARD = "preview";

    // only accessed in the EDT
    private static final List<PinnedPreviewWindow> openWindows = new ArrayList<>();
    private static int cascadeIndex;

    private static Tika tika;

    private final int luceneId;
    private final MultiViewer preview = new MultiViewer();
    private final HtmlLinkViewer linkViewer;
    private final JTabbedPane tabs = new JTabbedPane();
    private final JPanel previewCards = new JPanel(new CardLayout());
    private final JPanel metadataPanel = new JPanel(new BorderLayout());
    private final JButton searchButton, prevHitButton, nextHitButton, copyButton;
    private final ExecutorService loader;
    private final List<IItem> itemsToDispose = Collections.synchronizedList(new ArrayList<>());

    // only accessed in the EDT
    private MetadataViewer metadataViewer;

    private volatile boolean closed;
    private volatile IItem item, viewItem;
    private volatile String contentType, viewType;
    private volatile Set<String> highlights;
    private volatile IMultiBookmarks bookmarksListened;

    /**
     * Opens a pinned preview window for each item, asking for confirmation if
     * there are many. Must be called in the EDT.
     */
    public static void open(List<Integer> luceneIds) {
        if (luceneIds.isEmpty()) {
            return;
        }
        if (luceneIds.size() > MAX_ITEMS_WITHOUT_CONFIRMATION) {
            int option = JOptionPane.showConfirmDialog(App.get(),
                    Messages.getString("PinnedPreview.ConfirmMany", luceneIds.size()),
                    Messages.getString("MenuClass.PinPreview"), JOptionPane.YES_NO_OPTION);
            if (option != JOptionPane.YES_OPTION) {
                return;
            }
        }
        for (int luceneId : luceneIds) {
            new PinnedPreviewWindow(luceneId).setVisible(true);
        }
    }

    /**
     * Closes all pinned preview windows, e.g. before the case is closed or
     * reloaded. Must be called in the EDT.
     */
    public static void closeAll() {
        for (PinnedPreviewWindow window : new ArrayList<>(openWindows)) {
            window.close();
        }
    }

    public static void setBlurFilterAll(boolean enable) {
        for (PinnedPreviewWindow window : openWindows) {
            window.preview.setBlurFilter(enable);
        }
    }

    public static void setGrayFilterAll(boolean enable) {
        for (PinnedPreviewWindow window : openWindows) {
            window.preview.setGrayFilter(enable);
        }
    }

    /**
     * Updates the look and feel of all pinned preview windows, after the theme is
     * changed.
     */
    public static void updateUIAll() {
        for (PinnedPreviewWindow window : openWindows) {
            SwingUtilities.updateComponentTreeUI(window);
            window.reload();
        }
    }

    /**
     * Loads the content again, e.g. to apply a new theme to HTML content. Must be
     * called in the EDT.
     */
    private void reload() {
        MetadataViewer metadata = metadataViewer;
        loader.execute(() -> {
            if (closed) {
                return;
            }
            if (viewItem != null && viewType != null) {
                preview.loadFile(viewItem, viewType, highlights);
            }
            if (metadata != null && item != null) {
                metadata.loadFile(item, contentType, highlights);
            }
        });
    }

    private PinnedPreviewWindow(int luceneId) {
        this.luceneId = luceneId;

        linkViewer = ViewerController.addPreviewViewers(preview, this::showMetadata);
        App app = App.get();
        app.getViewerController().configureImageViewers(preview);
        preview.setBlurFilter(app.galleryModel.getBlurFilter());
        preview.setGrayFilter(app.galleryModel.getGrayFilter());

        searchButton = createButton("search", "MenuClass.SearchInViewer");
        searchButton.addActionListener(e -> preview.searchInViewer());
        prevHitButton = createButton("prev", "ViewerController.PrevHit");
        prevHitButton.addActionListener(e -> preview.scrollToNextHit(false));
        nextHitButton = createButton("next", "ViewerController.NextHit");
        nextHitButton.addActionListener(e -> preview.scrollToNextHit(true));
        copyButton = createButton("copy", "MenuClass.CopyViewerImage");
        copyButton.addActionListener(e -> preview.copyScreen());
        setButtonsEnabled(false);

        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.add(searchButton);
        toolBar.add(prevHitButton);
        toolBar.add(nextHitButton);
        toolBar.add(copyButton);

        JLabel loading = new JLabel(Messages.getString("App.Wait"), SwingConstants.CENTER);
        previewCards.add(loading, LOADING_CARD);
        previewCards.add(preview.getPanel(), PREVIEW_CARD);

        JPanel previewTab = new JPanel(new BorderLayout());
        previewTab.add(toolBar, BorderLayout.NORTH);
        previewTab.add(previewCards, BorderLayout.CENTER);
        tabs.addTab(preview.getName(), previewTab);
        tabs.addTab(Messages.getString("PinnedPreview.MetadataTab"), metadataPanel);
        tabs.addChangeListener(e -> {
            if (tabs.getSelectedComponent() == metadataPanel) {
                createMetadataViewer();
            }
        });
        getContentPane().add(tabs);

        setTitle(Messages.getString("PinnedPreview.Title", Messages.getString("App.Wait")));
        setIconImages(app.getIconImages());
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                close();
            }
        });
        setInitialBounds();

        loader = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "PinnedPreviewLoader");
            t.setDaemon(true);
            return t;
        });
        openWindows.add(this);
        loader.execute(this::load);
    }

    private JButton createButton(String icon, String tooltipKey) {
        JButton button = new JButton(IconUtil.getToolbarIcon(icon, resPath));
        button.setToolTipText(Messages.getString(tooltipKey));
        button.setFocusable(false);
        return button;
    }

    private void setButtonsEnabled(boolean enabled) {
        boolean hasHits = highlights != null && !highlights.isEmpty();
        int hitsSupported = preview.getHitsSupported();
        boolean hitsEnabled = enabled && ((hasHits && hitsSupported == 0) || hitsSupported == 1);
        prevHitButton.setEnabled(hitsEnabled);
        nextHitButton.setEnabled(hitsEnabled);
        searchButton.setEnabled(enabled && preview.isSearchSupported());
        copyButton.setEnabled(enabled && preview.getCurrentViewer() != null);
    }

    /**
     * Half the width and 70% of the height of the screen of the main window, in
     * cascade with the previous pinned windows.
     */
    private void setInitialBounds() {
        GraphicsConfiguration gc = App.get().getGraphicsConfiguration();
        Rectangle screen = gc.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        screen = new Rectangle(screen.x + insets.left, screen.y + insets.top, screen.width - insets.left - insets.right,
                screen.height - insets.top - insets.bottom);
        int width = screen.width / 2;
        int height = screen.height * 7 / 10;
        int x = screen.x + (screen.width - width) / 4 + cascadeIndex * CASCADE_OFFSET;
        int y = screen.y + (screen.height - height) / 4 + cascadeIndex * CASCADE_OFFSET;
        if (x + width > screen.x + screen.width || y + height > screen.y + screen.height) {
            cascadeIndex = 0;
            x = screen.x + (screen.width - width) / 4;
            y = screen.y + (screen.height - height) / 4;
        }
        cascadeIndex++;
        setBounds(x, y, width, height);
    }

    private void load() {
        try {
            // first, so viewers are always initialized before being released by close()
            preview.init();

            int luceneIdToShow = luceneId;
            Document doc = App.get().appCase.getSearcher().doc(luceneIdToShow);
            IPEDSource iCase = (IPEDSource) App.get().appCase.getAtomicSource(luceneIdToShow);
            IItem item = getItem(doc, iCase, false);
            String contentType = item.getMediaType() != null ? item.getMediaType().toString() : null;

            FileProcessor.waitEvidenceOpening(item);
            Tika tika = getTika();
            IItem viewItem = getViewItem(item, doc, iCase);
            String viewType = contentType != null ? ViewerController.detectViewType(tika, item, viewItem, contentType) : null;

            // UFED chat messages have their own preview, but the chat is more useful
            boolean isUfedChatMessage = MediaTypes.UFED_MESSAGE_MIME.equals(item.getMediaType())
                    && item.getMetadata().get(ExtraProperties.PARENT_VIEW_POSITION) != null;
            if (MenuClass.isChatMessage(item) && (isUfedChatMessage || viewType == null || !preview.isSupportedType(viewType, true))) {
                // show the parent chat scrolled to the message, as "go to parent chat"
                IItemId itemId = App.get().appCase.getItemId(luceneIdToShow);
                IIPEDSource atomicSource = App.get().appCase.getAtomicSourceBySourceId(itemId.getSourceId());
                int chatId = atomicSource.getParentId(itemId.getId());
                if (chatId != -1) {
                    linkViewer.setElementIDToScroll(item.getMetadata().get(ExtraProperties.PARENT_VIEW_POSITION));
                    luceneIdToShow = App.get().appCase.getLuceneId(new ItemId(itemId.getSourceId(), chatId));
                    doc = App.get().appCase.getSearcher().doc(luceneIdToShow);
                    iCase = (IPEDSource) App.get().appCase.getAtomicSource(luceneIdToShow);
                    item = getItem(doc, iCase, false);
                    contentType = item.getMediaType() != null ? item.getMediaType().toString() : null;
                    FileProcessor.waitEvidenceOpening(item);
                    viewItem = getViewItem(item, doc, iCase);
                    viewType = contentType != null ? ViewerController.detectViewType(tika, item, viewItem, contentType) : null;
                }
            }

            this.item = item;
            this.contentType = contentType;
            this.viewItem = viewItem;
            this.viewType = viewType;
            this.highlights = FileProcessor.getHighlightTerms(item);
            String name = item.getName();
            SwingUtilities.invokeLater(() -> setTitle(Messages.getString("PinnedPreview.Title", name)));

            if (closed) {
                return;
            }
            if (viewType != null && preview.isSupportedType(viewType, true)) {
                preview.loadFile(viewItem, viewType, highlights);
                // keeps chat checkboxes in sync with the main window
                IMultiBookmarks bookmarks = App.get().appCase.getMultiBookmarks();
                bookmarks.addSelectionListener(linkViewer);
                bookmarksListened = bookmarks;
                SwingUtilities.invokeLater(() -> {
                    ((CardLayout) previewCards.getLayout()).show(previewCards, PREVIEW_CARD);
                    setButtonsEnabled(true);
                });
            } else {
                SwingUtilities.invokeLater(() -> {
                    showMessage(Messages.getString("PinnedPreview.NoPreview"));
                    showMetadata();
                });
            }

        } catch (Exception e) {
            LOGGER.error("Error loading pinned preview of item " + luceneId, e);
            SwingUtilities.invokeLater(() -> showMessage(Messages.getString("PinnedPreview.Error", e.toString())));
        }
    }

    private static synchronized Tika getTika() {
        if (tika == null) {
            tika = new Tika();
        }
        return tika;
    }

    /**
     * Shows a message in place of the preview. Must be called in the EDT.
     */
    private void showMessage(String message) {
        previewCards.remove(previewCards.getComponent(0));
        previewCards.add(new JLabel(message, SwingConstants.CENTER), LOADING_CARD, 0);
        ((CardLayout) previewCards.getLayout()).show(previewCards, LOADING_CARD);
    }

    private IItem getViewItem(IItem item, Document doc, IPEDSource iCase) throws IOException {
        if (item.getViewFile() != null || item.hasPreview()) {
            return getItem(doc, iCase, true);
        }
        return item;
    }

    private IItem getItem(Document doc, IPEDSource iCase, boolean viewItem) throws IOException {
        IItem item = IndexItem.getItem(doc, iCase, viewItem);
        if (item == null) {
            throw new IOException("Item not found: " + doc.get(IndexItem.PATH));
        }
        itemsToDispose.add(item);
        return item;
    }

    /**
     * Shows the metadata tab. Must be called in the EDT.
     */
    private void showMetadata() {
        if (!closed) {
            tabs.setSelectedComponent(metadataPanel);
        }
    }

    /**
     * Creates the metadata viewer when its tab is shown for the first time, to
     * avoid creating its web views in every window. Must be called in the EDT.
     */
    private void createMetadataViewer() {
        if (metadataViewer != null || closed) {
            return;
        }
        MetadataViewer viewer = new MetadataViewer() {
            @Override
            public boolean isNumeric(String field) {
                return IndexItem.isNumeric(field);
            }
        };
        metadataViewer = viewer;
        metadataPanel.add(viewer.getPanel(), BorderLayout.CENTER);
        metadataPanel.revalidate();
        loader.execute(() -> {
            viewer.init();
            // waits the item if it is still loading, as the loader is single threaded
            if (!closed && item != null) {
                viewer.loadFile(item, contentType, highlights);
            }
        });
    }

    /**
     * Closes this window and releases its resources. Must be called in the EDT.
     */
    private void close() {
        if (closed) {
            return;
        }
        closed = true;
        openWindows.remove(this);
        if (openWindows.isEmpty()) {
            cascadeIndex = 0;
        }
        setVisible(false);
        MetadataViewer metadata = metadataViewer;
        loader.execute(() -> {
            if (bookmarksListened != null) {
                bookmarksListened.removeSelectionListener(linkViewer);
            }
            // releases temporary files and opened documents
            preview.loadFile(null);
            if (metadata != null) {
                metadata.loadFile(null);
            }
            for (AbstractViewer viewer : preview.getViewerList()) {
                if (viewer instanceof IcePDFViewer) {
                    ((IcePDFViewer) viewer).waitPendingLoad();
                }
            }
            Runnable disposeViewers = () -> {
                try {
                    preview.dispose();
                    if (metadata != null) {
                        metadata.dispose();
                    }
                } catch (RuntimeException e) {
                    LOGGER.warn("Error disposing pinned preview viewers", e);
                } finally {
                    dispose();
                }
            };
            try {
                // lets JavaFX finish pending tasks before disposing the Swing components
                Platform.runLater(() -> SwingUtilities.invokeLater(disposeViewers));
            } catch (IllegalStateException e) {
                // JavaFX was already shut down
                SwingUtilities.invokeLater(disposeViewers);
            }
            // viewers may still be reading the items, as in FileProcessor
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
            }
            synchronized (itemsToDispose) {
                for (IItem i : itemsToDispose) {
                    i.dispose();
                }
            }
        });
        loader.shutdown();
    }
}
