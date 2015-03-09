package edu.buffalo.cse.cse486586.groupmessenger2;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * GroupMessengerActivity is the main Activity for the assignment.
 *
 * @author stevko
 */
public class GroupMessengerActivity extends Activity {

    /* Keeps track of proposals received.
     * key = messageId, value = messageObject */
    static Map<String, List<Message>> proposalsReceived = new HashMap<>();

    /* How many messages have been sent by this device */
    static int messagesSent = 0;

    /* Keeps track of the last message delivered */
    static int seqNumOfLastDelivered = 0;

    /* My port number (this device's port number) */
    static String myPortNumber = null;

    /* Hold-back Queue (HBQ) and deliveryQueue
     * key = sequence no.
     * value = Message */
    static TreeMap<String, Message> holdBackQueue = new TreeMap<>(new CustomComparator());
    static TreeMap<String, Message> deliveryQueue = new TreeMap<>(new CustomComparator());

    static final String TAG = GroupMessengerActivity.class.getSimpleName();
    final int SERVER_PORT = 10000;
    final String[] GROUP = {"11108", "11112", "11116", "11120", "11124"};

    Uri uri;
    TextView textView = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_messenger);

        /* Build URI */
        uri = OnPTestClickListener.buildUri(OnPTestClickListener.URI_SCHEME, OnPTestClickListener.URI);

        /* TextView: To display all messages.
        *  EditText: To type in a message */
        textView = (TextView) findViewById(R.id.textView1);
        textView.setMovementMethod(new ScrollingMovementMethod());
        final EditText editText = (EditText) findViewById(R.id.editText1);
        final Button sendButton = (Button) findViewById(R.id.button4);

        /*
         * Calculate the port number that this AVD listens on.
         * It is just a hack that I came up with to get around the networking limitations of AVDs.
         * The explanation is provided in the PA1 spec.
         */
        TelephonyManager tel = (TelephonyManager) this.getSystemService(Context.TELEPHONY_SERVICE);
        String portStr = tel.getLine1Number().substring(tel.getLine1Number().length() - 4);
        myPortNumber = String.valueOf((Integer.parseInt(portStr) * 2));

        try {
            /* Create a server socket and a thread (AsyncTask) that listens on the server port */
            ServerSocket serverSocket = new ServerSocket(SERVER_PORT);
            new ServerTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, serverSocket);
        } catch (IOException e) {
            Log.e(TAG, "Can't create a ServerSocket");
            e.printStackTrace();
            return;
        }


        /*
         * button4 = Send
         *
         * Register an OnClickListener for the "Send" button.
         * In your implementation you need to get the message from the input box (EditText)
         * and send it to other AVDs.
         */
        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String msg = editText.getText().toString() + "\n";
//                String msg = editText.getText().toString();
                editText.setText(""); // Reset the input box.
                Log.d("DEBUG", "Send button clicked. Message: " + msg);

                new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, String.valueOf(CommunicationMode.MESSAGE), msg, myPortNumber);
            }
        });

        /*
         * When ENTER is pressed.
         *
         * Register an OnKeyListener for the input box. OnKeyListener is an event handler that
         * processes each key event. The purpose of the following code is to detect an enter key
         * press event, and create a client thread so that the client thread can send the string
         * in the input box over the network.
         */
        editText.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if ((event.getAction() == KeyEvent.ACTION_DOWN) &&
                        (keyCode == KeyEvent.KEYCODE_ENTER)) {
                    String msg = editText.getText().toString() + "\n";
                    editText.setText(""); // Reset the input box.
                    Log.d("DEBUG", "Enter pressed. Sending message: " + msg);

                    new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, String.valueOf(CommunicationMode.MESSAGE), msg, myPortNumber);
                    return true;
                }
                return false;
            }
        });

        /*
         * button1 = PTest
         * Registers OnPTestClickListener for "button1" in the layout, which is the "PTest" button.
         * OnPTestClickListener demonstrates how to access a ContentProvider.
         */
        findViewById(R.id.button1).setOnClickListener(
                new OnPTestClickListener(textView, getContentResolver(), this));

    }

    /* No need to implement */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.activity_group_messenger, menu);
        return true;
    }


    /* Refresh the content of the TextView */
    private void showChatOnTextView() {
        StringBuilder allMessages = new StringBuilder("");
        for (int i = 0; i <= seqNumOfLastDelivered; i++) {
            Cursor resultCursor = getContentResolver().query(uri, null, String.valueOf(i), null, null);
            String cursorText = getTextFromCursor(resultCursor);
            if (cursorText != null && cursorText.length() > 0)
                allMessages.append(i + ": " + cursorText + "\n");
//                allMessages.append(cursorText);
        }

        /* Display all the messages onto the text-view */
        textView.setText(allMessages.toString());
    }

    public String getTextFromCursor(Cursor cursor) {
        String messageText = null;
        if (cursor != null) {
            int keyIndex = cursor.getColumnIndex(OnPTestClickListener.KEY_FIELD);
            int valueIndex = cursor.getColumnIndex(OnPTestClickListener.VALUE_FIELD);

            if (keyIndex != -1 && valueIndex != -1) {

                cursor.moveToFirst();
                if (!(cursor.isFirst() && cursor.isLast())) {
                    Log.e(TAG, "Wrong number of rows in cursor");
                    Log.e(TAG, "cursor.isFirst(): " + cursor.isFirst() + ", cursor.isLast(): " + cursor.isLast());
                } else {
                    messageText = cursor.getString(valueIndex);
                }
            }
            cursor.close();
        }
        return messageText;
    }

    /**
     * ClientTask is an AsyncTask that should send a string over the network.
     * It is created by ClientTask.executeOnExecutor() call whenever OnKeyListener.onKey() detects
     * an enter key press event.
     *
     * @author stevko
     */
    private class ClientTask extends AsyncTask<Object, Void, Void> {

        @Override
        protected Void doInBackground(Object... msgs) {

            String modeString = (String) msgs[0];
            CommunicationMode communicationMode = CommunicationMode.valueOf(modeString);

            /* Misc declarations */
            Socket socket = null;
            DataOutputStream outputStream;
            ObjectOutputStream objectOutputStream = null;

            try {

                if (communicationMode == CommunicationMode.MESSAGE) {

                    /* Get message-text & port number of the sender */
                    String messageText = (String) msgs[1];
                    String myPortNumber = (String) msgs[2];
                    String msgId = myPortNumber + "_" + messagesSent++;

//                    Log.d("DEBUG", "messagesSent ==> " + (messagesSent - 1));

                    /* Wrap the message text and ID */
                    Message messageToSend = new Message(messageText, msgId, false);
                    messageToSend.setCommunicationMode(CommunicationMode.MESSAGE);

                    /* B-multicast the message to all ports in the GROUP */
                    for (int remoteHostNumber = 0; remoteHostNumber < GROUP.length; remoteHostNumber++) {
                        String remotePort = GROUP[remoteHostNumber];

                        socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                                Integer.parseInt(remotePort));

//                        Log.d("DEBUG", "Multicasting message to: " + remotePort);

                            /* Client code that sends out a message. */
                        if (socket != null) {
                            outputStream = new DataOutputStream(socket.getOutputStream());
                            if (outputStream != null) {
                                objectOutputStream = new ObjectOutputStream(new BufferedOutputStream(outputStream));
                                objectOutputStream.writeObject(messageToSend);
                                objectOutputStream.flush();
                            }
                        }
                    }
                } else if (communicationMode == CommunicationMode.PROPOSAL) {
                    /* Get message received */
                    Message messageObject = (Message) msgs[1];
                    String messageId = messageObject.getMessageId();
                    /* Extract port number from the message ID */
                    String destinationPort = messageId.substring(0, messageId.indexOf("_"));

                    /* Set the mode as PROPOSAL (for the server to interpret it) */
                    messageObject.setCommunicationMode(CommunicationMode.PROPOSAL);

//                    Log.d("DEBUG", "[" + messageId + "] Sending proposal to " + destinationPort + " ==> " + messageObject.getProposedSeqNumber());

                    /* Send the PROPOSAL as a UNICAST */
                    socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                            Integer.parseInt(destinationPort));
                    if (socket != null) {
                        outputStream = new DataOutputStream(socket.getOutputStream());
                        if (outputStream != null) {
                            objectOutputStream = new ObjectOutputStream(new BufferedOutputStream(outputStream));
                            objectOutputStream.writeObject(messageObject);
                            objectOutputStream.flush();
                        }
                    }

                } else if (communicationMode == CommunicationMode.AGREED_PROPOSAL) {

                    /* Do a B-Multicast to send the agreed proposal to everyone */
                    Message messageObjectToSend = (Message) msgs[1];
                    messageObjectToSend.setCommunicationMode(CommunicationMode.AGREED_PROPOSAL);

//                    Log.d("DEBUG", "Sending agreed proposal to all: " + messageObjectToSend.getAgreedSeqNumber());

                    for (int remoteHostNumber = 0; remoteHostNumber < GROUP.length; remoteHostNumber++) {
                        String remotePort = GROUP[remoteHostNumber];

                        socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                                Integer.parseInt(remotePort));

                            /* Client code that sends out a message. */
                        if (socket != null) {
                            outputStream = new DataOutputStream(socket.getOutputStream());
                            if (outputStream != null) {
                                objectOutputStream = new ObjectOutputStream(new BufferedOutputStream(outputStream));
                                objectOutputStream.writeObject(messageObjectToSend);
                                objectOutputStream.flush();
                            }
                        }
                    }

                }

            } catch (UnknownHostException e) {
                Log.e(TAG, "ClientTask UnknownHostException");
            } catch (IOException e) {
                Log.e(TAG, "ClientTask socket IOException");
            } catch (Exception e) {
                Log.e("ERROR", e.getStackTrace().toString());
            } finally {
                try {
                    if (socket != null)
                        socket.close();
                    if (objectOutputStream != null) {
                        objectOutputStream.flush();
                        objectOutputStream.close();
                    }
                } catch (IOException e) {
                    Log.e(TAG, "ClientTask socket IOException");
                }
            }

            return null;
        }
    }

    /* ServerTask is an AsyncTask that should handle incoming messages. */
    private class ServerTask extends AsyncTask<ServerSocket, String, Void> {

        @Override
        protected Void doInBackground(ServerSocket... sockets) {
            ServerSocket serverSocket = sockets[0];

            /* Server code that receives messages and passes them to onProgressUpdate(). */
            Socket clientSocket;
            String messages[] = new String[1000];

            try {
                while (true) {
                    clientSocket = serverSocket.accept();

                    ObjectInputStream objectInputStream = new ObjectInputStream(new BufferedInputStream(clientSocket.getInputStream()));
                    Message messageObject = (Message) objectInputStream.readObject();

                    CommunicationMode communicationMode = messageObject.getCommunicationMode();

                    if (communicationMode == CommunicationMode.MESSAGE) {
                        /* A message was received. Make a proposal for it's sequence number */

                        /* ========= Figuring out a proposal ========== */
                        /* Get the highest sequence number from the HBQ */
                        int maxSeqNumber = 0;
                        if (!holdBackQueue.isEmpty()) {
                            String lastKey = holdBackQueue.lastKey();
                            maxSeqNumber = Integer.parseInt(lastKey.substring(0, lastKey.indexOf(".")));
                        }
                        /* If the deliveryQueue had a higher seq num, get that */
//                        if (!deliveryQueue.isEmpty()) {
//                            String lastKey = deliveryQueue.lastKey();
//                            int lastSeqNumber = Integer.parseInt(lastKey.substring(0, lastKey.indexOf(".")));
//                            if (lastSeqNumber > maxSeqNumber)
//                                maxSeqNumber = lastSeqNumber;
//                        }
                        /* If the last delivered number was even higher, get that */
                        if (seqNumOfLastDelivered > maxSeqNumber)
                            maxSeqNumber = seqNumOfLastDelivered;

                        /* Set the proposal as 1 more than the highest seq number.
                         * Also, append the port number to avoid race condition. */
                        String proposedSeqNumber = String.valueOf(maxSeqNumber + 1) + "." + myPortNumber;
                        messageObject.setProposedSeqNumber(proposedSeqNumber);

                        /* Put your proposal in the HBQ */
                        holdBackQueue.put(proposedSeqNumber, messageObject);

                        /* Send proposal to the sender */
                        new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, String.valueOf(CommunicationMode.PROPOSAL), messageObject);

                    } else if (communicationMode == CommunicationMode.PROPOSAL) {

                        /* Get list of proposals received until now */
                        List<Message> proposalList = proposalsReceived.get(messageObject.getMessageId());
                        /* If the proposal list is empty, initialize it */
                        if (proposalList == null)
                            proposalList = new ArrayList<>();

                        /* Add this proposal to list of proposals received */
                        proposalList.add(messageObject);
                        proposalsReceived.put(messageObject.getMessageId(), proposalList);

                        Log.d("DEBUG", "[" + messageObject.getMessageId() + "] Proposal received: " + messageObject.getProposedSeqNumber() + ". Num of proposals received: " + proposalList.size());

                        /* Only if ALL proposals have been received */
                        /* TODO: What if 1 of the devices crashed? Need to avoid infinite loop */
                        if (proposalList.size() == GROUP.length) {

                            /* Accept the highest proposal */
                            String highestSeqNumber = "";
                            for (Message messageWithProposal : proposalList) {
                                String proposedSeqNumber = messageWithProposal.getProposedSeqNumber();
                                if (proposedSeqNumber.compareTo(highestSeqNumber) > 0)
                                    highestSeqNumber = proposedSeqNumber;
                            }

                            Log.d("DEBUG", "All proposals received. Winner ==> " + highestSeqNumber);

                            /* Now that we've ACCEPTED a proposal, let everyone know what it is */
                            messageObject.setAgreedSeqNumber(highestSeqNumber);
                            new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR,
                                    String.valueOf(CommunicationMode.AGREED_PROPOSAL), messageObject);
                        }

                    } else if (communicationMode == CommunicationMode.AGREED_PROPOSAL) {

                        /* We get the agreed proposal here. */
                        String agreedSeqNumber = messageObject.getAgreedSeqNumber();
                        String messageId = messageObject.getMessageId();

                        Log.d("DEBUG", "[" + messageId + "] Agreed seq no. received ==> " + agreedSeqNumber);

                        /* Find the sequence num corresponding to this message ID in the HBQ */
                        String existingSeqNumberForThisMessage = "";
                        Iterator<String> keySetIterator = holdBackQueue.keySet().iterator();
                        while (keySetIterator.hasNext()) {
                            String seqNum = keySetIterator.next();
                            Message hbqMessage = holdBackQueue.get(seqNum);

                            if (hbqMessage.getMessageId().equals(messageId)) {
                                /* Found. This is the message for which we just received an agreed sequence number */
                                existingSeqNumberForThisMessage = seqNum;
                                break;
                            }
                        }

                        /* Is the new (agreed) sequence number greater than the existing seq number? */
                        if (agreedSeqNumber.compareTo(existingSeqNumberForThisMessage) > 0) {
                            /* Remove the existing entry in the HBQ */
                            /* TODO: Should I be retaining any info in it's message? I think not. */
                            holdBackQueue.remove(existingSeqNumberForThisMessage);

                            existingSeqNumberForThisMessage = agreedSeqNumber;
                        }

                        /* Mark as DELIVERABLE (i.e. ready-to-deliver) */
                        messageObject.setDeliverable(true);
                        /* Put the new (agreed) sequence number on the HBQ */
                        holdBackQueue.put(existingSeqNumberForThisMessage, messageObject);
                        Log.d("DEBUG", "Before transferring to deliveryQueue: " + hbqToString());

                        /* If front of the holdBackQueue has any deliverables,
                         * transfer them to the delivery queue */
                        while (true) {
                            if (!holdBackQueue.isEmpty()) {
                                Message firstMessageInQueue = holdBackQueue.get(holdBackQueue.firstKey());
                                if (firstMessageInQueue.isDeliverable()) {
                                    /* Remove from HBQ, and add to deliveryQueue */
                                    deliveryQueue.put(holdBackQueue.firstKey(), firstMessageInQueue);
                                    holdBackQueue.remove(holdBackQueue.firstKey());
                                } else
                                    break;
                            } else
                                break;
                        }

                        /* Deliver stuff that can be delivered in the delivery queue */
                        synchronized (this) {
                            deliver();
                        }
                    }

                }

            } catch (IOException e) {
                Log.e("ERROR", e.getStackTrace().toString());
            } catch (Exception e) {
                Log.e("ERROR", e.getStackTrace().toString());
            }

            return null;
        }

        private synchronized void deliver() {

            /* TODO: Check if this method has been implemented properly */
            try {
                int lastNum = seqNumOfLastDelivered;
                List<String> deliverThese = new ArrayList<>();

                /* Iterate through the delivery queue and mark messages that can be delivered right away */
                Log.d("DEBUG", "DeliveryQueue -->> " + deliveryQueue.keySet());
                Log.d("DEBUG", "seqNumOfLastDelivered => " + seqNumOfLastDelivered);

                if (!deliveryQueue.isEmpty()) {

                    Iterator<String> keySetIterator = deliveryQueue.keySet().iterator();
                    while (keySetIterator.hasNext()) {
                        String sequenceNum = keySetIterator.next();

                        /* Remove the pid. If the seqNum was 3.2, then strippedSequenceNum = 3 */
                        int strippedSequenceNum = Integer.parseInt(sequenceNum.substring(0, sequenceNum.indexOf(".")));

                        /* If this number is 1 more than (or same as) the last seen seqNum,
                         * then it can be delivered */
 // TODO: Temporarily removing this condition. Might need this to make it FIFO
 //                        if (strippedSequenceNum == lastNum + 1 || strippedSequenceNum == lastNum) {
                        if (true) {
                            deliverThese.add(sequenceNum);
                            lastNum = strippedSequenceNum;
                        } else
                            break;
                    }

                    Log.d("DEBUG", "DELIVER THESE -->> " + deliverThese);

                    for (String deliverThisSeqNum : deliverThese) {

                        /* TODO: Should we be stripping the seq num before putting it in the Content Provider?
                         * TODO: What happens when there's 2.1 and 2.2 - they'll both get stripped to 2 */
                        int strippedSequenceNum = Integer.parseInt(deliverThisSeqNum.substring(0, deliverThisSeqNum.indexOf(".")));
                        String messageToDeliver = deliveryQueue.get(deliverThisSeqNum).getMessageText();

                        /* Subtract 1 because sequence begins from 0 */
// TODO:                int deliverySeqNum = (strippedSequenceNum - 1);
                        int deliverySeqNum = seqNumOfLastDelivered;
                        Log.d("DEBUG", "DELIVERING -> " + deliverySeqNum + ", msg: " + messageToDeliver);

                        /* Remove the entry from the deliveryQueue */
                        deliveryQueue.remove(deliverThisSeqNum);

                        /* Deliver */
                        ContentValues contentValue = new ContentValues();
                        contentValue.put(OnPTestClickListener.KEY_FIELD, Integer.toString(deliverySeqNum));
                        contentValue.put(OnPTestClickListener.VALUE_FIELD, messageToDeliver);
                        getContentResolver().insert(uri, contentValue);

                        Log.d("DEBUG", "ContentValues: " + Integer.toString(deliverySeqNum) + " ==> " + messageToDeliver);

                        /* This is the sequence number of the message that has just been delivered */
// TODO:                seqNumOfLastDelivered = strippedSequenceNum;
                        seqNumOfLastDelivered++;
                    }

                    publishProgress();
                }
            } catch (Exception e) {
                Log.e("ERROR", e.getStackTrace().toString());
            }
        }

        protected void onProgressUpdate(String... strings) {

            /* Refresh the content of the TextView */
            showChatOnTextView();
            return;
        }

        protected String hbqToString() {
            StringBuffer hbqString = new StringBuffer("HBQ [key, msgID, isDeliverable, proposedSeqNum, agreedSeqNum]:\n ");
            Iterator<String> iterator = holdBackQueue.keySet().iterator();
            while (iterator.hasNext()) {
                String key = iterator.next();
                Message messageObject = holdBackQueue.get(key);
                hbqString.append("[" + key + ", " + messageObject.getMessageId() + ", " + messageObject.isDeliverable() + ", " + messageObject.getProposedSeqNumber() + ", " + messageObject.getAgreedSeqNumber() + "] --> ");
            }
            return hbqString.toString();
        }
    }

    public enum CommunicationMode {
        MESSAGE, PROPOSAL, AGREED_PROPOSAL
    }

    static class CustomComparator implements Comparator<String> {
        @Override
        public int compare(String s1, String s2) {
            double d1 = Double.parseDouble(s1);
            double d2 = Double.parseDouble(s2);
            if (d1 < d2) return -1;
            else if (d1 > d2) return 1;
            else return 0;
        }
    };
}