package edu.buffalo.cse.cse486586.groupmessenger2;

/**
 * Created by ankitkap on 3/8/15.
 */
public class Message {

    String messageText;
    String messageId;
    boolean isDeliverable;
    String proposedSeqNumber;
    String agreedSeqNumber;
    GroupMessengerActivity.CommunicationMode communicationMode;
    String deviceIdOfProposer;
    String localSequenceNumber;

    static String separator = "##";

    public String stringify() {
        String escapedText = messageText.replaceAll("\n", "\\\\n");
        String s = (escapedText + separator + messageId + separator + String.valueOf(isDeliverable) + separator
                + proposedSeqNumber + separator + agreedSeqNumber + separator + communicationMode.toString()
                + separator + deviceIdOfProposer + separator + localSequenceNumber);
        return s;
    }

    public static Message assembleObjectFromString(String s) {
        Message msg = new Message();

        String unescapedString = s.replaceAll("\\\\n", "\n");
        String[] parts = unescapedString.split(separator);

        msg.setMessageText(parts[0]);
        msg.setMessageId(parts[1]);
        msg.setDeliverable(Boolean.parseBoolean(parts[2]));
        msg.setProposedSeqNumber(parts[3]);
        msg.setAgreedSeqNumber(parts[4]);
        msg.setCommunicationMode(GroupMessengerActivity.CommunicationMode.valueOf(parts[5]));
        msg.setDeviceIdOfProposer(parts[6]);
        msg.setLocalSequenceNumber(parts[7]);

        return msg;
    }

    public Message() {}
    public Message(String messageText, String messageId, boolean isDeliverable) {
        this.messageText = messageText;
        this.messageId = messageId;
        this.isDeliverable = isDeliverable;
    }

    public String getLocalSequenceNumber() {
        return localSequenceNumber;
    }

    public void setLocalSequenceNumber(String localSequenceNumber) {
        this.localSequenceNumber = localSequenceNumber;
    }

    public String getDeviceIdOfProposer() {
        return deviceIdOfProposer;
    }

    public void setDeviceIdOfProposer(String deviceIdOfProposer) {
        this.deviceIdOfProposer = deviceIdOfProposer;
    }

    public String getProposedSeqNumber() {
        return proposedSeqNumber;
    }

    public void setProposedSeqNumber(String proposedSeqNumber) {
        this.proposedSeqNumber = proposedSeqNumber;
    }

    public GroupMessengerActivity.CommunicationMode getCommunicationMode() {
        return communicationMode;
    }

    public void setCommunicationMode(GroupMessengerActivity.CommunicationMode communicationMode) {
        this.communicationMode = communicationMode;
    }

    public String getAgreedSeqNumber() {
        return agreedSeqNumber;
    }

    public void setAgreedSeqNumber(String agreedSeqNumber) {
        this.agreedSeqNumber = agreedSeqNumber;
    }
    public boolean isDeliverable() {
        return isDeliverable;
    }
    public void setDeliverable(boolean isDeliverable) {
        this.isDeliverable = isDeliverable;
    }
    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getMessageText() {
        return messageText;
    }

    public void setMessageText(String messageText) {
        this.messageText = messageText;
    }
}
