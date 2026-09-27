package com.poyrax.yandex;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

public final class TestDocumentsProvider extends DocumentsProvider {
    private File root;
    private static final String[] COLUMNS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE};

    @Override
    public boolean onCreate() { root = new File(getContext().getFilesDir(), "documents"); return root.exists() || root.mkdirs(); }

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(new String[]{Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_AVAILABLE_BYTES});
        cursor.addRow(new Object[]{"root", "root", "Test", Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_IS_CHILD, root.getUsableSpace()});
        return cursor;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection == null ? COLUMNS : projection);
        add(cursor, resolve(documentId));
        return cursor;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection == null ? COLUMNS : projection);
        File[] children = resolve(parentDocumentId).listFiles();
        if (children != null) for (File child : children) add(cursor, child);
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        return ParcelFileDescriptor.open(resolve(documentId), ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String name) throws FileNotFoundException {
        if (name.contains("/") || name.equals("..")) throw new FileNotFoundException();
        File child = new File(resolve(parentDocumentId), name);
        if (child.exists()) throw new FileNotFoundException("exists");
        try {
            boolean made = mimeType.equals(Document.MIME_TYPE_DIR) ? child.mkdir() : child.createNewFile();
            if (!made) throw new FileNotFoundException();
            return id(child);
        } catch (IOException e) { throw new FileNotFoundException(e.getMessage()); }
    }

    @Override
    public String renameDocument(String documentId, String name) throws FileNotFoundException {
        File source = resolve(documentId);
        File target = new File(source.getParentFile(), name);
        if (target.exists() || !source.renameTo(target)) throw new FileNotFoundException();
        return id(target);
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        if (!resolve(documentId).delete()) throw new FileNotFoundException();
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        try { return resolve(documentId).getCanonicalPath().startsWith(resolve(parentDocumentId).getCanonicalPath() + File.separator); }
        catch (IOException e) { return false; }
    }

    private File resolve(String value) throws FileNotFoundException {
        try {
            File file = value.equals("root") ? root : new File(root, value.substring("root/".length()));
            if (!file.getCanonicalPath().equals(root.getCanonicalPath()) && !file.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)) throw new FileNotFoundException();
            return file;
        } catch (IOException | RuntimeException e) { throw new FileNotFoundException(); }
    }

    private String id(File file) { return file.equals(root) ? "root" : "root/" + root.toURI().relativize(file.toURI()).getPath().replaceAll("/$", ""); }

    private void add(MatrixCursor cursor, File file) {
        int flags = Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME | (file.isDirectory() ? Document.FLAG_DIR_SUPPORTS_CREATE : Document.FLAG_SUPPORTS_WRITE);
        String name = file.equals(root) ? "Test" : file.isDirectory() && root.equals(file.getParentFile()) ? "İndirilenler" : file.getName();
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            switch (column) {
                case Document.COLUMN_DOCUMENT_ID: row.add(id(file)); break;
                case Document.COLUMN_DISPLAY_NAME: row.add(name); break;
                case Document.COLUMN_MIME_TYPE: row.add(file.isDirectory() ? Document.MIME_TYPE_DIR : "application/octet-stream"); break;
                case Document.COLUMN_FLAGS: row.add(flags); break;
                case Document.COLUMN_SIZE: row.add(file.length()); break;
                default: row.add(null);
            }
        }
    }
}
