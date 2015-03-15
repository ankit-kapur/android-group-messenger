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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
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

    /* Timeouts */
    final int PROPOSAL_TIMEOUT = 6000;
    final int AGREEMENT_TIMEOUT = 10000;

    /* Max proposal number and agreed number */
    static int maxProposedNum = 0;
    static int maxAgreedProposalNum = 0;

    /* Locks to avoid concurrent modification exceptions on lists */
    ReentrantLock hbqAccessLock = new ReentrantLock(true);
    ReentrantLock deviceGroupLock = new ReentrantLock(true);
    ReentrantLock deliveryQueueLock = new ReentrantLock(true);
    ReentrantLock deliverMethodLock = new ReentrantLock(true);
    ReentrantLock agreedProposalLock = new ReentrantLock(true);
    ReentrantLock proposalBlockLock = new ReentrantLock(true);
    ReentrantLock msgBlockLock = new ReentrantLock(true);

    /* Keeps track of proposals received.
     * key = messageId, value = messageObject */
    static Map<String, List<MessageWrapper>> proposalsReceived = new HashMap<>();

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
    static TreeMap<String, MessageWrapper> holdBackQueue = new TreeMap<>(new CustomComparator());
    static TreeMap<String, MessageWrapper> deliveryQueue = new TreeMap<>(new CustomComparator());

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
            Timer timer = new Timer();
            PrintWriter printWriter;
            ObjectOutputStream objectOutputStream = null;

            try {

                if (communicationMode == CommunicationMode.MESSAGE) {
                    /* --- Send a message --- */

                    msgBlockLock.lock();
                    try {
                        /* Get message-text & port number of the sender */
                        String messageText = (String) msgs[1];
                        final String myPortNumber = (String) msgs[2];
                        final String msgId = myPortNumber + "_" + messagesSent++;

                        /* Wrap the message text and ID */
                        final MessageWrapper messageWrapperToSend = new MessageWrapper(messageText, msgId, false);
                        messageWrapperToSend.setCommunicationMode(CommunicationMode.MESSAGE);

                        /* B-multicast the message to all ports in the DEVICE_GROUP */
                        deviceGroupLock.lock();
                        try {
                            for (String devicePort : DEVICE_GROUP) {
                                Log.d("DEBUG", "Multicasting message to: " + devicePort);
                                try {
                                    socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                                            Integer.parseInt(devicePort));

                                /* Client code that sends out a message. */
                                    printWriter = new PrintWriter(socket.getOutputStream(), true);
                                    printWriter.println(messageWrapperToSend.stringify());
                                } catch (IOException e) {
                                    Log.e(TAG, "Unable to send message to port " + devicePort + ". Can't find device.");
                                }

                            /* Set the proposal state as FALSE for this message-device combo
                             * It will become TRUE when a proposal is accepted from the device (which
                             * corresponds to devicePort) for this message */
                                String msgAndDeviceId = msgId + "$$" + devicePort;
                                proposalState.put(msgAndDeviceId, false);
                            }
                        } finally {
                            deviceGroupLock.unlock();
                        }
                        /* Wait and check if all proposals have been received */
                        Log.d("DEBUG", "Waiting... to check if all proposals were received.");

                        timer.schedule(new TimerTask() {
                            public void run() {
                                List<String> portRemovalList = new ArrayList<>();

                                deviceGroupLock.lock();
                                for (String devicePort : DEVICE_GROUP) {

                                    /* If a proposal has not been received yet,
                                     * the device is dead. Remove it from DEVICE_GROUP */
                                    String msgAndDeviceId = msgId + "$$" + devicePort;
                                    if (!proposalState.get(msgAndDeviceId))
                                        portRemovalList.add(devicePort);
                                }
                                deviceGroupLock.unlock();

                                /* If there was at least one proposer DEATH */
//                                if (!portRemovalList.isEmpty()) {
                                if (false) {

                                    Log.d("DEBUG", "[" + msgId + "] DEATH ==> No proposals from " + portRemovalList);
                                    Log.d("DEBUG", "Removing " + portRemovalList + " from DEVICE_GROUP " + DEVICE_GROUP);

                                    /* Remove devices from the DEVICE_GROUP */
                                    deviceGroupLock.lock();
                                    DEVICE_GROUP.removeAll(portRemovalList);
                                    deviceGroupLock.unlock();

                                    /* Send a notice to myself to move on. Don't
                                     * wait for a proposal from the dead devices */
                                    messageWrapperToSend.setCommunicationMode(CommunicationMode.DEAD_PROPOSAL);
                                    try {
                                        Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                                                Integer.parseInt(myPortNumber));

                                        PrintWriter printWriter = new PrintWriter(socket.getOutputStream(), true);
                                        printWriter.println(messageWrapperToSend.stringify());
                                    } catch (IOException e) {
                                        Log.e("ERROR", Log.getStackTraceString(e));
                                    }
                                }
                                this.cancel();
                            }
                        }, PROPOSAL_TIMEOUT);
                    } finally {
                        msgBlockLock.unlock();
                    }
                } else if (communicationMode == CommunicationMode.PROPOSAL) {
                    /* --- Send a proposal --- */
                    proposalBlockLock.lock();
                    try {
                        /* The message received here contains the proposed seq no.
                        * (made by this device's server) */
                        final MessageWrapper messageWrapperObject = (MessageWrapper) msgs[1];
                        final String messageId = messageWrapperObject.getMessageId();
                        /* Extract port number from the message ID */
                        final String destinationPort = messageId.substring(0, messageId.indexOf("_"));

                        /* Set the mode as PROPOSAL (for the server to interpret it) */
                        messageWrapperObject.setCommunicationMode(CommunicationMode.PROPOSAL);
                        /* Set MY device ID to help identify who this proposal came from */
                        messageWrapperObject.setDeviceIdOfProposer(myPortNumber);

//                      Log.d("DEBUG", "[" + messageId + "] Sending proposal to " + destinationPort + " ==> " + messageObject.getProposedSeqNumber());

                        /* Send the PROPOSAL as a UNICAST */
                        socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                                Integer.parseInt(destinationPort));

                        printWriter = new PrintWriter(socket.getOutputStream(), true);
                        printWriter.println(messageWrapperObject.stringify());

                        Log.d("DEBUG", "[" + messageId + "] Waiting... to check if an agreement to this proposal has been received.");
                        /* Wait and check if all proposals have been received */
                        agreedProposalState.put(messageId, false);

                        timer.schedule(new TimerTask() {
                            public void run() {

                                /* If an agreed proposal has not been received by now,
                                 * then the sender is dead. Remove it from DEVICE_GROUP */

                                //                                if (!agreedProposalState.get(messageId)) {
                                if (false) {

                                    Log.e("ERROR", "[" + messageId + "] DEATH! ==> No agreement received");

                                    /* Remove this device from your device list */
                                    deviceGroupLock.lock();
                                    DEVICE_GROUP.remove(destinationPort);
                                    deviceGroupLock.unlock();

                                    /* Ask all other devices to drop this message from their HBQs */
                                    messageWrapperObject.setCommunicationMode(CommunicationMode.DROP_FROM_HBQ);
                                    deviceGroupLock.lock();
                                    for (String devicePortNum : DEVICE_GROUP) {
                                        try {
                                            Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                                                    Integer.parseInt(devicePortNum));

                                            PrintWriter printWriter = new PrintWriter(socket.getOutputStream(), true);
                                            printWriter.println(messageWrapperObject.stringify());
                                        } catch (IOException e) {
                                            Log.e("ERROR", Log.getStackTraceString(e));
                                        }
                                    }
                                    deviceGroupLock.unlock();

                                    /* Send a notice to myself - to move on. Don't
                                     * wait for a proposal from the dead guys */
                                    messageWrapperObject.setCommunicationMode(CommunicationMode.DEAD_PROPOSAL);
                                    try {
                                        Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                                                Integer.parseInt(myPortNumber));

                                        PrintWriter printWriter = new PrintWriter(socket.getOutputStream(), true);
                                        printWriter.println(messageWrapperObject.stringify());
                                    } catch (IOException e) {
                                        Log.e("ERROR", Log.getStackTraceString(e));
                                    }
                                }
                                this.cancel();
                            }
                        }, AGREEMENT_TIMEOUT);
                    } finally {
                        proposalBlockLock.unlock();
                    }
                } else if (communicationMode == CommunicationMode.AGREED_PROPOSAL) {
                    agreedProposalLock.lock();
                    try {
                        /* Do a B-Multicast to send the agreed proposal to everyone */
                        MessageWrapper messageWrapperObjectToSend = (MessageWrapper) msgs[1];
                        messageWrapperObjectToSend.setCommunicationMode(CommunicationMode.AGREED_PROPOSAL);

                        Log.d("DEBUG", "Sending agreed proposal to all: " + messageWrapperObjectToSend.getAgreedSeqNumber());

                        deviceGroupLock.lock();
                        for (int remoteHostNumber = 0; remoteHostNumber < DEVICE_GROUP.size(); remoteHostNumber++) {
                            String remotePort = DEVICE_GROUP.get(remoteHostNumber);

                            socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                                    Integer.parseInt(remotePort));

                            /* Client code that sends out a message. */
                            printWriter = new PrintWriter(socket.getOutputStream(), true);
                            printWriter.println(messageWrapperObjectToSend.stringify());
                        }
                        deviceGroupLock.unlock();

                    } finally {
                        agreedProposalLock.unlock();
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
            MessageWrapper messageWrapperObject;

            try {
                while (true) {
                    clientSocket = serverSocket.accept();

//                    ObjectInputStream objectInputStream = new ObjectInputStream(new BufferedInputStream(clientSocket.getInputStream()));
//                    messageObject = (Message) objectInputStream.readObject();

//                    Print printWriter = new PrintWriter(socket.getOutputStream());
//                    printWriter.println(messageToSend.stringify());

                    BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                    messageWrapperObject = MessageWrapper.assembleObjectFromString(bufferedReader.readLine());

                    String msgId = messageWrapperObject.getMessageId();

                    CommunicationMode communicationMode = messageWrapperObject.getCommunicationMode();

                    if (communicationMode == CommunicationMode.MESSAGE) {
                        /* A message was received. Make a proposal for it's sequence number */

                        msgBlockLock.lock();
                        try {
                            /* ========= Figuring out a proposal ========== */
                            /* Get the highest sequence number from the HBQ */
//                            int maxSeqNumber = 0;
//                            if (!holdBackQueue.isEmpty()) {
//                                String lastKey = holdBackQueue.lastKey();
//                                maxSeqNumber = Integer.parseInt(lastKey.substring(0, lastKey.indexOf(".")));
//                            }

                        /* If the deliveryQueue had a higher seq num, get that */
//                                                    if (!deliveryQueue.isEmpty()) {
//                                                        String lastKey = deliveryQueue.lastKey();
//                                                        int lastSeqNumber = Integer.parseInt(lastKey.substring(0, lastKey.indexOf(".")));
//                                                        if (lastSeqNumber > maxSeqNumber)
//                                                            maxSeqNumber = lastSeqNumber;
//                                                    }

                            /* If the last delivered number was even higher, get that */
//                            if (seqNumOfLastDelivered > maxSeqNumber)
//                                maxSeqNumber = seqNumOfLastDelivered;

                            int proposedNumber = (maxProposedNum > maxAgreedProposalNum) ? maxProposedNum : maxAgreedProposalNum;
                            maxProposedNum = proposedNumber + 1;

                            /* Set the proposal as 1 more than the highest seq number.
                             * Also, append the port number to avoid race condition. */
                            String proposedSeqNumber = String.valueOf(maxProposedNum) + "." + myPortNumber;
                            messageWrapperObject.setProposedSeqNumber(proposedSeqNumber);

                            Log.d("DEBUG", "Proposing sequence num " + proposedSeqNumber + " for msg [" + msgId + "]");

                            /* Put your proposal in the HBQ */
                            holdBackQueue.put(proposedSeqNumber, messageWrapperObject);

                            /* Send proposal to the sender */
                            new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, String.valueOf(CommunicationMode.PROPOSAL), messageWrapperObject);
                        } finally {
                            msgBlockLock.unlock();
                        }
                    } else if (communicationMode == CommunicationMode.PROPOSAL || communicationMode == CommunicationMode.DEAD_PROPOSAL) {

                        /* --- Proposal received --- */

                        proposalBlockLock.lock();
                        try {
                            /* Get list of proposals received until now */
                            List<MessageWrapper> proposalList = proposalsReceived.get(messageWrapperObject.getMessageId());

                            /* If the proposal list is empty, initialize it */
                            if (proposalList == null)
                                proposalList = new ArrayList<>();

                            if (communicationMode == CommunicationMode.PROPOSAL) {

                                /* Mark this proposal as 'received' (true) */
                                String msgAndDeviceId = messageWrapperObject.getMessageId() + "$$" + messageWrapperObject.getDeviceIdOfProposer();
                                proposalState.put(msgAndDeviceId, true);

                                /* Add this proposal to list of proposals received */
                                proposalList.add(messageWrapperObject);
                                proposalsReceived.put(messageWrapperObject.getMessageId(), proposalList);

                                Log.d("DEBUG", "[" + messageWrapperObject.getMessageId() + "] Proposal received: " + messageWrapperObject.getProposedSeqNumber() + ". Num of proposals received: " + proposalList.size());
                            } else {
                                Log.d("DEBUG", "[" + messageWrapperObject.getMessageId() + "] DEAD_PROPOSAL");
                                Log.d("DEBUG", "DEVICE_GROUP ==> " + DEVICE_GROUP);
                            }

                            /* Only if ALL proposals have been received */
                            if (proposalList.size() == DEVICE_GROUP.size()) {

                                /* Accept the highest proposal */
                                String highestSeqNumber = "";
                                for (MessageWrapper messageWrapperWithProposal : proposalList) {
                                    String proposedSeqNumber = messageWrapperWithProposal.getProposedSeqNumber();
                                    if (proposedSeqNumber.compareTo(highestSeqNumber) > 0)
                                        highestSeqNumber = proposedSeqNumber;
                                }

                                Log.d("DEBUG", "All proposals received. Winner ==> " + highestSeqNumber);

                                /* Now that we've ACCEPTED a proposal, let everyone know what it is */
                                messageWrapperObject.setAgreedSeqNumber(highestSeqNumber);
                                new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR,
                                        String.valueOf(CommunicationMode.AGREED_PROPOSAL), messageWrapperObject);
                            }
                        } finally {
                            proposalBlockLock.unlock();
                        }
                    } else if (communicationMode == CommunicationMode.AGREED_PROPOSAL) {
                        /* We get the agreed proposal here. */

                        agreedProposalLock.lock();
                        try {
                            String agreedSeqNumber = messageWrapperObject.getAgreedSeqNumber();
                            String messageId = messageWrapperObject.getMessageId();

                            /* Record this as the highest agreed proposal num received yet */
                            int currentAgreedNumber = Integer.parseInt(agreedSeqNumber.substring(0, agreedSeqNumber.indexOf(".")));
                            maxAgreedProposalNum = (currentAgreedNumber > maxAgreedProposalNum) ? currentAgreedNumber : maxAgreedProposalNum;

                            /* Record the fact that an agreed-proposal was received for this message */
                            agreedProposalState.put(messageId, true);

                            Log.d("DEBUG", "[" + messageId + "] Agreed seq num received ==> " + agreedSeqNumber);

                            /* Find the sequence num corresponding to this message ID in the HBQ */
                            String existingSeqNumberForThisMessage = "";
                            hbqAccessLock.lock();
                            try {
                                for (String seqNum : holdBackQueue.keySet()) {
                                    MessageWrapper hbqMessageWrapper = holdBackQueue.get(seqNum);

                                    if (hbqMessageWrapper.getMessageId().equals(messageId)) {
                                    /* Found. This is the message for which we just received an agreed sequence number */
                                        existingSeqNumberForThisMessage = seqNum;
                                        break;
                                    }
                                }
                            } finally {
                                hbqAccessLock.unlock();
                            }

                            /* Is the new (agreed) sequence number greater than the existing seq number? */
                            hbqAccessLock.lock();
                            if (agreedSeqNumber.compareTo(existingSeqNumberForThisMessage) > 0) {
                                /* Remove the existing entry in the HBQ */
                                Log.d("DEBUG", "HBQ before removing proposed seq num: " + hbqToString());
                                holdBackQueue.remove(existingSeqNumberForThisMessage);
                                existingSeqNumberForThisMessage = agreedSeqNumber;
                            }
                            hbqAccessLock.unlock();

                            /* Mark as DELIVERABLE (i.e. ready-to-deliver) */
                            messageWrapperObject.setDeliverable(true);
                            /* Put the new (agreed) sequence number on the HBQ */
                            holdBackQueue.put(existingSeqNumberForThisMessage, messageWrapperObject);
                            Log.d("DEBUG", "Before transferring to deliveryQueue: " + hbqToString());

                            /* Wait a while before making the delivery */
                            /* TODO: Currently wait time set to zero */
                            Timer timer = new Timer();
                            timer.schedule(new TimerTask() {
                                public void run() {
                                    /* If front of the holdBackQueue has any deliverables,
                                    * transfer them to the delivery queue */

                                    hbqAccessLock.lock();
                                    try {
                                        while (true) {
                                            if (!holdBackQueue.isEmpty()) {
                                                MessageWrapper firstMessageWrapperInQueue = null;
                                                try {
                                                    firstMessageWrapperInQueue = holdBackQueue.get(holdBackQueue.firstKey());
                                                } catch (NoSuchElementException e) {
                                                    Log.e("ERROR", e.getMessage());
                                                    Log.e("ERROR", hbqToString());
                                                    break;
                                                }

                                                if (firstMessageWrapperInQueue.isDeliverable()) {

                                                    /* Remove from HBQ, and add to deliveryQueue */
                                                    deliveryQueue.put(holdBackQueue.firstKey(), firstMessageWrapperInQueue);
                                                    holdBackQueue.remove(holdBackQueue.firstKey());
                                                } else
                                                    break;
                                            } else
                                                break;
                                        }
                                    } finally {
                                        hbqAccessLock.unlock();
                                    }
                                    /* Deliver stuff that can be delivered in the delivery queue */
                                    deliver();
                                }
                            }, 0);

                        } finally {
                            agreedProposalLock.unlock();
                        }
                    } else if (communicationMode == CommunicationMode.DROP_FROM_HBQ) {
                        /* Drop this message ID from the HBQ */
                        hbqAccessLock.lock();
                        try {
                            String seqNumToDrop = null;
                            String debugString = "messageId: " + messageWrapperObject.getMessageId() + ", HBQ msgId: ";
                            for (String seqNum : holdBackQueue.keySet()) {
                                debugString += holdBackQueue.get(seqNum).getMessageId() + ", ";
                                if (holdBackQueue.get(seqNum).getMessageId().equals(messageWrapperObject.getMessageId())) {
                                    seqNumToDrop = seqNum;
                                    break;
                                }
                            }

                            /* Drop this message */
                            if (seqNumToDrop != null)
                                holdBackQueue.remove(seqNumToDrop);
                            else
                                Log.e("ERROR", debugString);

                        } finally {
                            hbqAccessLock.unlock();
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

            deliverMethodLock.lock();
            try {
                List<String> deliverThese = new ArrayList<>();
                if (!deliveryQueue.isEmpty()) {

                    /* Iterate through the delivery queue and mark messages that can be delivered right away */
                    deliveryQueueLock.lock();
                    Log.d("DEBUG", "DeliveryQueue -->> " + deliveryQueue.keySet());
                    Log.d("DEBUG", "seqNumOfLastDelivered => " + seqNumOfLastDelivered);

                    for (String sequenceNum : deliveryQueue.keySet()) {
                        /* If this number is 1 more than (or same as) the last seen seqNum,
                         * then it can be delivered */

                        if (true) {
                            deliverThese.add(sequenceNum);
                        } else
                            break;
                    }
                    deliveryQueueLock.unlock();

                    Log.d("DEBUG", "DELIVER THESE -->> " + deliverThese);

                    for (String deliverThisSeqNum : deliverThese) {

                        String messageToDeliver = deliveryQueue.get(deliverThisSeqNum).getMessageText();

                        /* Subtract 1 because sequence begins from 0 */
                        int deliverySeqNum = seqNumOfLastDelivered;
                        Log.d("DEBUG", "\nDELIVERING -> " + deliverySeqNum + ", msg: [" + deliveryQueue.get(deliverThisSeqNum).getMessageId() + "] " + messageToDeliver + " -> " + deliveryQueue.get(deliverThisSeqNum).getAgreedSeqNumber());

                        /* Remove the entry from the deliveryQueue */
                        deliveryQueueLock.lock();
                        deliveryQueue.remove(deliverThisSeqNum);
                        deliveryQueueLock.unlock();

                        /* Deliver */
                        ContentValues contentValue = new ContentValues();
                        contentValue.put(OnPTestClickListener.KEY_FIELD, Integer.toString(deliverySeqNum));
                        contentValue.put(OnPTestClickListener.VALUE_FIELD, messageToDeliver);
                        getContentResolver().insert(uri, contentValue);

                        /* This is the sequence number of the message that has just been delivered */
                        seqNumOfLastDelivered++;
                    }

                    publishProgress();
                }
            } catch (Exception e) {
                Log.e("ERROR", Log.getStackTraceString(e));
            } finally {
                deliverMethodLock.unlock();
            }
        }

        protected void onProgressUpdate(String... strings) {

            /* Refresh the content of the TextView */
            showChatOnTextView();
            return;
        }

        protected String hbqToString() {
            StringBuffer hbqString = new StringBuffer("HBQ [key, msgID, isDeliverable, proposedSeqNum, agreedSeqNum]:\n ");
            hbqAccessLock.lock();
            try {
                for (String key : holdBackQueue.keySet()) {
                    MessageWrapper messageWrapperObject = holdBackQueue.get(key);
                    hbqString.append("[" + key + ", " + messageWrapperObject.getMessageId() + ", " + messageWrapperObject.isDeliverable() + ", " + messageWrapperObject.getProposedSeqNumber() + ", " + messageWrapperObject.getAgreedSeqNumber() + "] --> ");
                }
            } finally {
                hbqAccessLock.unlock();
            }
            return hbqString.toString();
        }
    }

    public enum CommunicationMode {
        MESSAGE, PROPOSAL, AGREED_PROPOSAL, DEAD_PROPOSAL, DEAD_AGREEMENT, DROP_FROM_HBQ
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
}