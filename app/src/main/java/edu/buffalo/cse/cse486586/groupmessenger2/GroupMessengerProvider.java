package edu.buffalo.cse.cse486586.groupmessenger2;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * GroupMessengerProvider is a key-value table. Once again, please note that we do not implement
 * full support for SQL as a usual ContentProvider does. We re-purpose ContentProvider's interface
 * to use it as a key-value table.
 *
 * Please read:
 *
 * http://developer.android.com/guide/topics/providers/content-providers.html
 * http://developer.android.com/reference/android/content/ContentProvider.html
 *
 * before you start to get yourself familiarized with ContentProvider.
 *
 * There are two methods you need to implement---insert() and query(). Others are optional and
 * will not be tested.
 *
 * @author stevko
 *
 */
public class GroupMessengerProvider extends ContentProvider {

    Context context;
    final String fileExtension = ".dat";

    @Override
    public boolean onCreate() {

        /* Get context (to be used in the writer/reader methods */
        context = getContext();

        return false;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        /*
         * You need to implement this method. Note that values will have two columns (a key
         * column and a value column) and one row that contains the actual (key, value) pair to be
         * inserted.
         * 
         * For actual storage, you can use any option. If you know how to use SQL, then you can use
         * SQLite. But this is not a requirement. You can use other storage options, such as the
         * internal storage option that we used in PA1. If you want to use that option, please
         * take a look at the code for PA1.
         */


        /* Get the key and value */
        String key = (String) values.get(OnPTestClickListener.KEY_FIELD);
        String value = (String) values.get(OnPTestClickListener.VALUE_FIELD);
        String fileName = key + fileExtension;

        /* Write the content values to internal storage */
        writeToInternalStorage(fileName, value);

//      Log.v("insert", values.stringify());
        return uri;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        /*
         * You need to implement this method. Note that you need to return a Cursor object
         * with the right format. If the formatting is not correct, then it is not going to work.
         * 
         * If you use SQLite, whatever is returned from SQLite is a Cursor object. However, you
         * still need to be careful because the formatting might still be incorrect.
         * 
         * If you use a file storage option, then it is your job to build a Cursor * object. I
         * recommend building a MatrixCursor described at:
         * http://developer.android.com/reference/android/database/MatrixCursor.html
         */

        /* Make the cursor */
        String[] columnNames = new String[2];
        columnNames[0] = OnPTestClickListener.KEY_FIELD;
        columnNames[1] = OnPTestClickListener.VALUE_FIELD;
        MatrixCursor matrixCursor = new MatrixCursor(columnNames);

        /* Read from the file. "selection" is the key */
        String fileContent = readFromInternalStorage(selection + fileExtension);

        if (fileContent != null && fileContent.length() > 0) {
            String[] columnValues = new String[2];
            columnValues[0] = selection;
            columnValues[1] = fileContent;
            matrixCursor.addRow(columnValues);
        }

        return matrixCursor;
    }

    private void writeToInternalStorage(String fileName, String contentOfFile) {
        try {
            FileOutputStream stream = context.openFileOutput(fileName, Context.MODE_WORLD_WRITEABLE);
            stream.write(contentOfFile.getBytes());
            stream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String readFromInternalStorage(String fileName) {
        String contentOfFile = "";
        try {
            File file = context.getFileStreamPath(fileName);
            if (file.exists()) {
                FileInputStream stream = context.openFileInput(fileName);
                int byteContent;
                if (stream != null) {
                    while ((byteContent = stream.read()) != -1)
                        contentOfFile += (char) byteContent;
                    stream.close();
                }
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return contentOfFile;
    }





    /* ------ No need to implement these ------ */
    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        // You do not need to implement this.
        return 0;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        // You do not need to implement this.
        return 0;
    }

    @Override
    public String getType(Uri uri) {
        // You do not need to implement this.
        return null;
    }
}