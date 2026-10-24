package iped.parsers.whatsapp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 *
 * @author Fabio Melo Pfeifer <pfeifer.fmp@pf.gov.br>
 */
public class Chat {

    private long id;
    private final WAContact remote;
    private String subject;
    private List<Message> messages = new ArrayList<>();
    private String title;
    private boolean isGroupChat;
    private boolean isChannelChat;
    private boolean isBroadcast;
    private boolean isDeleted;

    private String recoveredFrom;

    private Integer messageCounter;
    private Integer storedMessagesCount;
    private List<MessageSequenceGap> sequenceGaps = new ArrayList<>();

    private Set<WAContact> groupMembers = new HashSet<>();

    public Chat(WAContact remote) {
        this.remote = remote;
        if (remote != null && remote.getFullId().equals(WAContact.waStatusBroadcast)) {
            setBroadcast(true);
        }
    }

    /**
     * @return the id
     */
    public long getId() {
        return id;
    }

    /**
     * @param id
     *            the id to set
     */
    public void setId(long id) {
        this.id = id;
    }

    /**
     * @return the subject
     */
    public String getSubject() {
        return subject;
    }

    /**
     * @param subject
     *            the subject to set
     */
    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getPrintId() {
        return remote.getId();
    }

    /**
     * @return the messages
     */
    public List<Message> getMessages() {
        return messages;
    }

    public void add(Message message) {
        messages.add(message);
    }

    /**
     * @param messages
     *            the messages to set
     */
    public void setMessages(List<Message> messages) {
        this.messages = messages;
    }

    public boolean isGroupChat() {
        return isGroupChat;
    }

    public void setGroupChat(boolean isGroupChat) {
        this.isGroupChat = isGroupChat;
    }

    public boolean isChannelChat() {
        return isChannelChat;
    }

    public void setChannelChat(boolean isChannelChat) {
        this.isChannelChat = isChannelChat;
    }

    public boolean isGroupOrChannelChat() {
        return isGroupChat || isChannelChat;
    }

    public boolean isBroadcast() {
        return isBroadcast;
    }

    public void setBroadcast(boolean isBroadcast) {
        this.isBroadcast = isBroadcast;
    }

    public String getTitle() {
        if (title == null) {
            title = "WhatsApp ";
            if (isChannelChat()) {
                title += "Channel";
                if (getSubject() != null && !getSubject().isBlank()) {
                    title += " - " + getSubject().strip();
                }
            } else if (isGroupChat()) {
                title += "Group";
                if (getSubject() != null && !getSubject().isBlank()) {
                    title += " - " + getSubject().strip();
                }
            } else {
                if (isBroadcast()) {
                    title += "Status";
                } else {
                    title += "Chat";
                }
                if (remote != null && remote.getName() != null
                        && (getPrintId() == null || !remote.getName().strip().equals(getPrintId().strip()))) {
                    title += " - " + remote.getName().strip(); //$NON-NLS-1$
                }
            }
            if (getPrintId() != null && !getPrintId().isBlank()) {
                title += " - " + getPrintId().strip();
            }
        }
        return title;
    }

    public WAContact getRemote() {
        return remote;
    }

    public String getRecoveredFrom() {
        return recoveredFrom;
    }

    public void setRecoveredFrom(String recoveredFrom) {
        this.recoveredFrom = recoveredFrom;
    }

    public Set<WAContact> getGroupMembers() {
        return groupMembers;
    }

    public void setGroupMembers(Set<WAContact> groupmembers) {
        this.groupMembers = groupmembers;
    }

    public void setDeleted(boolean isDeleted) {
        this.isDeleted = isDeleted;
    }

    public boolean isDeleted() {
        return isDeleted;
    }

    /**
     * @return the message counter kept by WhatsApp for this chat, which is not
     *         decremented when messages are deleted, or null if not available
     */
    public Integer getMessageCounter() {
        return messageCounter;
    }

    public void setMessageCounter(Integer messageCounter) {
        this.messageCounter = messageCounter;
    }

    /**
     * @return the number of messages of this chat still stored in the database,
     *         or null if not available
     */
    public Integer getStoredMessagesCount() {
        return storedMessagesCount;
    }

    public void setStoredMessagesCount(Integer storedMessagesCount) {
        this.storedMessagesCount = storedMessagesCount;
    }

    /**
     * @return gaps in the sequence numbers of the messages still stored for this
     *         chat
     */
    public List<MessageSequenceGap> getSequenceGaps() {
        return sequenceGaps;
    }
}
