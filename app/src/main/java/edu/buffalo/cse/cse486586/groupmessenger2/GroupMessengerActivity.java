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
import java.util.Iterator;
import java.util.List;
import java.util.TreeMap;

/**
 * GroupMessengerActivity is the main Activity for the assignment.
 *
 * @author stevko
 */
public class GroupMessengerActivity extends Activity {

    /* Keeps track of proposals received */
    List<Message> proposalsReceived = new ArrayList<>();

    /* How many messages have been sent by this device */
    static int messagesSent = 0;

    /* Keeps track of the last message delivered */
    static int seqNumOfLastDelivered = 0;

    /* Hold-back Queue (HBQ) and deliveryQueue
     * key = sequence no.
     * value = Message */
    TreeMap<String, Message> holdBackQueue = new TreeMap<>();
    TreeMap<String, Message> deliveryQueue = new TreeMap<>();

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
        final String myPort = String.valueOf((Integer.parseInt(portStr) * 2));

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
                editText.setText(""); // Reset the input box.
                Log.d("DEBUG", "Send button clicked. Message: " + msg);

                new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, String.valueOf(CommunicationMode.MESSAGE), msg, myPort);
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

                    new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, String.valueOf(CommunicationMode.MESSAGE), msg, myPort);
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
                allMessages.append(cursorText + "\n");
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

                    System.out.print("messagesSent ==> " + messagesSent);

                    /* Wrap the message text and ID */
                    Message messageToSend = new Message(messageText, msgId, false);

                    /* B-multicast the message to all ports in the GROUP */
                    for (int remoteHostNumber = 0; remoteHostNumber < GROUP.length; remoteHostNumber++) {
                        String remotePort = GROUP[remoteHostNumber];

                        socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                                Integer.parseInt(remotePort));

                            /* Client code that sends out a message. */
                        if (socket != null) {
                            outputStream = new DataOutputStream(socket.getOutputStream());
                            if (outputStream != null) {
                                objectOutputStream = new ObjectOutputStream(new BufferedOutputStream(outputStream));
                                objectOutputStream.writeObject(messageToSend);
                            }
                        }
                    }
                } else if (communicationMode == CommunicationMode.PROPOSAL) {
                    /* Get message received */
                    Message messageObject = (Message) msgs[1];
                    String messageId = messageObject.getMessageId();
                    /* Extract port number from the message ID */
                    String destinationPort = messageId.substring(0, messageId.indexOf("_"));

                    /* Send the PROPOSAL as a unicast */
                    socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                            Integer.parseInt(destinationPort));
                    if (socket != null) {
                        outputStream = new DataOutputStream(socket.getOutputStream());
                        if (outputStream != null) {
                            objectOutputStream = new ObjectOutputStream(new BufferedOutputStream(outputStream));
                            objectOutputStream.writeObject(messageObject);
                        }
                    }

                } else if (communicationMode == CommunicationMode.ACCEPTED_PROPOSAL) {

                    Message messageObjectToSend = (Message) msgs[1];

                    /* Do a B-Multicast to send the accepted proposal to everyone */
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
                            }
                        }
                    }

                }

            } catch (UnknownHostException e) {
                Log.e(TAG, "ClientTask UnknownHostException");
            } catch (IOException e) {
                Log.e(TAG, "ClientTask socket IOException");
            } catch (Exception e) {
                e.printStackTrace();
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
                        /* A message was received */
                        String messageId = messageObject.getMessageId();
                        /* Extract port number from the message ID */
                        String portNumber = messageId.substring(0, messageId.indexOf("_"));

                        /* TODO: Need to put the message text somewhere? Mostly not */
                        String messageText = messageObject.getMessageText();

                        /* Get the highest sequence number from the HBQ */
                        int maxSeqNumber = 0;
                        if (!holdBackQueue.isEmpty())
                            maxSeqNumber = Integer.parseInt(holdBackQueue.lastKey());
                        /* Set the proposal as 1 more than the highest seq number.
                         * Also, append the port number to avoid race condition. */
                        String proposedSeqNumber = String.valueOf(maxSeqNumber + 1) + "." + portNumber;
                        messageObject.setProposedSeqNumber(proposedSeqNumber);

                        /* Put your proposal in the HBQ */
                        holdBackQueue.put(proposedSeqNumber, messageObject);

                        /* Send proposal to the sender */
                        new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, String.valueOf(CommunicationMode.PROPOSAL), messageObject);

                    } else if (communicationMode == CommunicationMode.PROPOSAL) {

                        /* Add this proposal to list of proposals received */
                        proposalsReceived.add(messageObject);

                        /* Only if ALL proposals have been received */
                        /* TODO: What if 1 of the devices crashed? Need to avoid infinite loop */
                        if (proposalsReceived.size() == GROUP.length) {

                            /* Accept the highest proposal */
                            String highestSeqNumber = "";
                            for (Message messageWithProposal : proposalsReceived) {
                                String proposedSeqNumber = messageWithProposal.getProposedSeqNumber();
                                if (proposedSeqNumber.compareTo(highestSeqNumber) > 0)
                                    highestSeqNumber = proposedSeqNumber;
                            }

                            /* Now that we've ACCEPTED a proposal, let everyone know what it is */
                            messageObject.setAgreedSeqNumber(highestSeqNumber);
                            new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR,
                                    String.valueOf(CommunicationMode.ACCEPTED_PROPOSAL), messageObject);
                        }

                    } else if (communicationMode == CommunicationMode.ACCEPTED_PROPOSAL) {

                        /* We get the agreed proposal here. */
                        String agreedSeqNumber = messageObject.getAgreedSeqNumber();
                        String messageId = messageObject.getMessageId();

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

                            /* Put the new (agreed) sequence number on the HBQ */
                            holdBackQueue.put(agreedSeqNumber, messageObject);
                            existingSeqNumberForThisMessage = agreedSeqNumber;
                        }

                        /* Mark as DELIVERABLE (i.e. ready-to-deliver) */
                        holdBackQueue.get(existingSeqNumberForThisMessage).setDeliverable(true);


                        /* If front of the holdBackQueue has any deliverables,
                         * transfer them to the delivery queue */
                        while (true) {
                            Message firstMessageInQueue = holdBackQueue.get(holdBackQueue.firstKey());
                            if (firstMessageInQueue.isDeliverable()) {
                                /* Remove from HBQ, and add to deliveryQueue */
                                deliveryQueue.put(holdBackQueue.firstKey(), firstMessageInQueue);
                                holdBackQueue.remove(holdBackQueue.firstKey());
                            } else
                                break;
                        }

                        /* Deliver stuff that can be delivered in the delivery queue */
                        deliver();
                    }

                }

            } catch (IOException e) {
                e.printStackTrace();
            } catch (Exception e) {
                e.printStackTrace();
            }

            return null;
        }

        private void deliver() {

            /* TODO: Check if this method has been implemented properly */

            int lastNum = seqNumOfLastDelivered;
            List<String> deliverThese = new ArrayList<>();

            /* Iterate through the delivery queue and mark messages that can be delivered right away */
            Iterator<String> keySetIterator = deliveryQueue.keySet().iterator();
            while (keySetIterator.hasNext()) {
                String sequenceNum = keySetIterator.next();

                /* Remove the pid. If the seqNum was 3.2, then strippedSequenceNum = 3 */
                int strippedSequenceNum = Integer.parseInt(sequenceNum.substring(0, sequenceNum.indexOf(".")));

                /* If this number is 1 more than (or same as) the last seen seqNum,
                 * then it can be delivered */
                if (strippedSequenceNum == lastNum + 1 || strippedSequenceNum == lastNum) {
                    deliverThese.add(sequenceNum);
                    lastNum = strippedSequenceNum;
                } else
                    break;
            }

            for (String deliverThisSeqNum: deliverThese) {

                /* TODO: Should we be stripping the seq num before putting it in the Content Provider? */
                int strippedSequenceNum = Integer.parseInt(deliverThisSeqNum.substring(0, deliverThisSeqNum.indexOf(".")));
                String messageToDeliver = deliveryQueue.get(deliverThisSeqNum).getMessageText();

                /* Remove the entry from the deliveryQueue */
                deliveryQueue.remove(deliverThisSeqNum);

                /* Deliver */
                ContentValues contentValue = new ContentValues();
                contentValue.put(OnPTestClickListener.KEY_FIELD, Integer.toString(strippedSequenceNum));
                contentValue.put(OnPTestClickListener.VALUE_FIELD, messageToDeliver);
                getContentResolver().insert(uri, contentValue);
            }

            publishProgress();
        }

        protected void onProgressUpdate(String... strings) {

            /* Refresh the content of the TextView */
            showChatOnTextView();
            return;
        }
    }

    public enum CommunicationMode {
        MESSAGE, PROPOSAL, ACCEPTED_PROPOSAL
    }
}
