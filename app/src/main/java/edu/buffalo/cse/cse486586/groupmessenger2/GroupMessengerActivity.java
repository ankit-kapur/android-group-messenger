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
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * GroupMessengerActivity is the main Activity for the assignment.
 *
 * @author stevko
 */
public class GroupMessengerActivity extends Activity {

    /* Read timeout of 500 ms */
    int PROPOSAL_TIMEOUT = 13000;
    int AGREEMENT_TIMEOUT = 15000;

    /* Keeps track of the local sequence numbers across ALL devices */
    static Map<String, Integer> seqNumTracker = new HashMap<>();

    /* Keeps track of proposals received.
     * key = messageId, value = messageObject */
    static Map<String, List<Message>> proposalsReceived = new HashMap<>();

    /* Keeps track of WHICH devices have sent proposals and which have not */
    static Map<String, Boolean> proposalState = new HashMap<>();
    static Map<String, Boolean> agreedProposalState = new HashMap<>();

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


    Uri uri;
    TextView textView = null;
    final int SERVER_PORT = 10000;
    List<String> DEVICE_GROUP = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_messenger);

        DEVICE_GROUP.add("11108");
        DEVICE_GROUP.add("11112");
        DEVICE_GROUP.add("11116");
        DEVICE_GROUP.add("11120");
        DEVICE_GROUP.add("11124");

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
            Log.getStackTraceString(e);
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
        String allMessages = "";
        for (int i = 0; i < seqNumOfLastDelivered; i++) {
            Cursor resultCursor = getContentResolver().query(uri, null, String.valueOf(i), null, null);
            String cursorText = getTextFromCursor(resultCursor);
            if (cursorText != null && cursorText.length() > 0)
                allMessages += (i + ": " + cursorText + "\n");
        }

        /* Display all the messages onto the text-view */
        textView.setText(allMessages);
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
                    /* --- Send a message --- */

                    ReentrantLock localLock = new ReentrantLock(true);
                    localLock.lock();
                    try {
                    /* Get message-text & port number of the sender */
                        String messageText = (String) msgs[1];
                        final String myPortNumber = (String) msgs[2];
                        final String msgId = myPortNumber + "_" + messagesSent++;

                    /* Wrap the message text and ID */
                        final Message messageToSend = new Message(messageText, msgId, false);
                        messageToSend.setCommunicationMode(CommunicationMode.MESSAGE);

                    /* B-multicast the message to all ports in the DEVICE_GROUP */
                        for (String devicePort : DEVICE_GROUP) {
                            Log.d("DEBUG", "Multicasting message to: " + devicePort);
                            try {
                                socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                                        Integer.parseInt(devicePort));

                            /* Client code that sends out a message. */
                                outputStream = new DataOutputStream(socket.getOutputStream());
                                objectOutputStream = new ObjectOutputStream(new BufferedOutputStream(outputStream));

                                objectOutputStream.writeObject(messageToSend);
                                objectOutputStream.flush();
                            } catch (IOException e) {
                                Log.e(TAG, "Unable to send message to port " + devicePort + ". Can't find device.");
                            }

                        /* Set the proposal state as FALSE for this message-device combo
                         * It will become TRUE when a proposal is accepted from the device (which
                         * corresponds to devicePort) for this message */
                            String msgAndDeviceId = msgId + "$$" + devicePort;
                            proposalState.put(msgAndDeviceId, false);
                        }

                        /* Wait and check if all proposals have been received */
                        Log.d("DEBUG", "Waiting... to check if all proposals were received.");

                        Timer timer = new Timer();
                        timer.schedule(new TimerTask() {
                            public void run() {
                                List<String> portRemovalList = new ArrayList<>();
                                for (String devicePort : DEVICE_GROUP) {

                            /* If a proposal was not received past it's timeout,
                            it's dead. Remove it from DEVICE_GROUP */
                                    String msgAndDeviceId = msgId + "$$" + devicePort;
                                    if (!proposalState.get(msgAndDeviceId))
                                        portRemovalList.add(devicePort);
                                }

                            /* If there was at least one DEATH */
                                if (!portRemovalList.isEmpty()) {

                                    Log.d("DEBUG", "DEATH ==> No proposals from " + portRemovalList);
                                    Log.d("DEBUG", "Removing " + portRemovalList + " from DEVICE_GROUP " + DEVICE_GROUP);

                                /* Remove devices from the DEVICE_GROUP */
                                    DEVICE_GROUP.removeAll(portRemovalList);

                                /* Send a notice to self - to move on. Don't wait for more proposals */
                                    messageToSend.setCommunicationMode(CommunicationMode.DEAD_PROPOSAL);
                                    try {
                                        Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                                                Integer.parseInt(myPortNumber));

                                        DataOutputStream outputStream = new DataOutputStream(socket.getOutputStream());
                                        ObjectOutputStream objectOutputStream = new ObjectOutputStream(new BufferedOutputStream(outputStream));
                                        objectOutputStream.writeObject(messageToSend);
                                        objectOutputStream.flush();
                                    } catch (IOException e) {
                                        Log.e("ERROR", Log.getStackTraceString(e));
                                    }
                                }
                                this.cancel();
                            }
                        }, PROPOSAL_TIMEOUT);
                    } finally {
                        localLock.unlock();
                    }
                } else if (communicationMode == CommunicationMode.PROPOSAL) {
                    /* --- Send a proposal --- */

                    ReentrantLock localLock = new ReentrantLock(true);
                    localLock.lock();
                    try {
                    /* The message received here contains the proposed seq no.
                     * (made by this device's server) */
                        Message messageObject = (Message) msgs[1];
                        final String messageId = messageObject.getMessageId();
                    /* Extract port number from the message ID */
                        final String destinationPort = messageId.substring(0, messageId.indexOf("_"));

                    /* Set the mode as PROPOSAL (for the server to interpret it) */
                        messageObject.setCommunicationMode(CommunicationMode.PROPOSAL);
                    /* Set MY device ID to help identify who this proposal came from */
                        messageObject.setDeviceIdOfProposer(myPortNumber);

//                    Log.d("DEBUG", "[" + messageId + "] Sending proposal to " + destinationPort + " ==> " + messageObject.getProposedSeqNumber());

                    /* Send the PROPOSAL as a UNICAST */
                        socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                                Integer.parseInt(destinationPort));
                        outputStream = new DataOutputStream(socket.getOutputStream());
                        objectOutputStream = new ObjectOutputStream(new BufferedOutputStream(outputStream));
                        objectOutputStream.writeObject(messageObject);
                        objectOutputStream.flush();

                        Log.d("DEBUG", "Waiting... to check if an agreement to this proposal has been received.");
                    /* Wait and check if all proposals have been received */
                        agreedProposalState.put(messageId, false);

                        Timer timer = new Timer();
                        timer.schedule(new TimerTask() {
                            public void run() {

                            /* If a proposal was not received past it's timeout,
                             * it's dead. Remove it from DEVICE_GROUP */
                                if (!agreedProposalState.get(messageId)) {
                                /* Remove this device from your device list */
                                    DEVICE_GROUP.remove(destinationPort);

                                /* Drop the message from the HBQ */
                                    String seqNumToDrop = null;
                                    for (String seqNum : holdBackQueue.keySet()) {
//                                        Log.d("DEBUG", "HBQ msgId: " + holdBackQueue.get(seqNum).getMessageId() + ", messageId: " + messageId);
                                        if (holdBackQueue.get(seqNum).getMessageId().equals(messageId)) {
                                            seqNumToDrop = seqNum;
                                            break;
                                        }
                                    }

                                    holdBackQueue.remove(seqNumToDrop);
                                }
                                this.cancel();
                            }
                        }, AGREEMENT_TIMEOUT);
                    } finally {
                        localLock.unlock();
                    }
                } else if (communicationMode == CommunicationMode.AGREED_PROPOSAL) {
                    ReentrantLock localLock = new ReentrantLock(true);
                    localLock.lock();
                    try {
                    /* Do a B-Multicast to send the agreed proposal to everyone */
                        Message messageObjectToSend = (Message) msgs[1];
                        messageObjectToSend.setCommunicationMode(CommunicationMode.AGREED_PROPOSAL);

                        Log.d("DEBUG", "Sending agreed proposal to all: " + messageObjectToSend.getAgreedSeqNumber());

                        for (int remoteHostNumber = 0; remoteHostNumber < DEVICE_GROUP.size(); remoteHostNumber++) {
                            String remotePort = DEVICE_GROUP.get(remoteHostNumber);

                            socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                                    Integer.parseInt(remotePort));

                        /* Client code that sends out a message. */
                            outputStream = new DataOutputStream(socket.getOutputStream());
                            objectOutputStream = new ObjectOutputStream(new BufferedOutputStream(outputStream));
                            objectOutputStream.writeObject(messageObjectToSend);
                            objectOutputStream.flush();
                        }

                    } finally {
                        localLock.unlock();
                    }
                }

            } catch (UnknownHostException e) {
                Log.e(TAG, "ClientTask UnknownHostException");
            } catch (IOException e) {
                Log.e(TAG, "ClientTask socket IOException: " + Log.getStackTraceString(e));
            } catch (Exception e) {
                Log.e("ERROR", Log.getStackTraceString(e));
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
            Message messageObject;

            try {
                while (true) {
                    clientSocket = serverSocket.accept();

                    ObjectInputStream objectInputStream = new ObjectInputStream(new BufferedInputStream(clientSocket.getInputStream()));
                    messageObject = (Message) objectInputStream.readObject();

                    CommunicationMode communicationMode = messageObject.getCommunicationMode();

                    if (communicationMode == CommunicationMode.MESSAGE) {
                        /* A message was received. Make a proposal for it's sequence number */

                        ReentrantLock localLock = new ReentrantLock(true);
                        localLock.lock();
                        try {
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
                        } finally {
                            localLock.unlock();
                        }
                    } else if (communicationMode == CommunicationMode.PROPOSAL || communicationMode == CommunicationMode.DEAD_PROPOSAL) {

                        /* --- Proposal received --- */

                        ReentrantLock localLock = new ReentrantLock(true);
                        localLock.lock();
                        try {
                        /* Get list of proposals received until now */
                            List<Message> proposalList = proposalsReceived.get(messageObject.getMessageId());

                        /* If the proposal list is empty, initialize it */
                            if (proposalList == null)
                                proposalList = new ArrayList<>();

                            if (communicationMode == CommunicationMode.PROPOSAL) {

                            /* Mark this proposal as 'received' (true) */
                                String msgAndDeviceId = messageObject.getMessageId() + "$$" + messageObject.getDeviceIdOfProposer();
                                proposalState.put(msgAndDeviceId, true);

                            /* Add this proposal to list of proposals received */
                                proposalList.add(messageObject);
                                proposalsReceived.put(messageObject.getMessageId(), proposalList);

                                Log.d("DEBUG", "[" + messageObject.getMessageId() + "] Proposal received: " + messageObject.getProposedSeqNumber() + ". Num of proposals received: " + proposalList.size());
                            } else {
                                Log.d("DEBUG", "DEAD_PROPOSAL");
                                Log.d("DEBUG", "DEVICE_GROUP ==> " + DEVICE_GROUP);
                            }

                        /* Only if ALL proposals have been received */
                            if (proposalList.size() == DEVICE_GROUP.size()) {

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
                        } finally {
                            localLock.unlock();
                        }
                    } else if (communicationMode == CommunicationMode.AGREED_PROPOSAL) {
                        /* We get the agreed proposal here. */

                        ReentrantLock localLock = new ReentrantLock(true);
                        localLock.lock();
                        try {
                            String agreedSeqNumber = messageObject.getAgreedSeqNumber();
                            String messageId = messageObject.getMessageId();

                            /* Record the fact that an agreed-proposal was received for this message */
                            agreedProposalState.put(messageId, true);

                            Log.d("DEBUG", "[" + messageId + "] Agreed seq num received ==> " + agreedSeqNumber);

                            /* Find the sequence num corresponding to this message ID in the HBQ */
                            String existingSeqNumberForThisMessage = "";
                            for (String seqNum : holdBackQueue.keySet()) {
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
                            deliver();
                        } finally {
                            localLock.unlock();
                        }
                    }
                }

            } catch (IOException e) {
                Log.e("ERROR", Log.getStackTraceString(e));
            } catch (Exception e) {
                Log.e("ERROR", Log.getStackTraceString(e));
            }

            return null;
        }

        private void deliver() {

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
                Log.e("ERROR", Log.getStackTraceString(e));
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
        MESSAGE, PROPOSAL, AGREED_PROPOSAL, DEAD_PROPOSAL, DEAD_AGREEMENT
    }

    static class CustomComparator implements Comparator<String> {
        @Override
        public int compare(String s1, String s2) {
//            if (s1 != null && s2 != null) {
            double d1 = Double.parseDouble(s1);
            double d2 = Double.parseDouble(s2);
            if (d1 < d2) return -1;
            else if (d1 > d2) return 1;
            else return 0;
//            }
        }
    }

    ;
}