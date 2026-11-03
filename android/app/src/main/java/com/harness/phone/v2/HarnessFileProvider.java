package com.harness.phone.v2;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Serves app-private downloaded files to external viewers on Android 8/9.
 * Android 10+ downloads use MediaStore content URIs directly.
 */
public class HarnessFileProvider extends ContentProvider {
    public static final String AUTHORITY = "com.harness.phone.v2.files";

    public static Uri uriFor(File file) {
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath("downloads")
                .appendPath(file.getName())
                .build();
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        if (uri == null || uri.getPathSegments().size() != 2
                || !"downloads".equals(uri.getPathSegments().get(0))) {
            throw new FileNotFoundException("unsupported uri");
        }
        File base = getContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (base == null) base = getContext().getFilesDir();
        File target = new File(base, uri.getPathSegments().get(1));
        try {
            String basePath = base.getCanonicalPath() + File.separator;
            if (!target.getCanonicalPath().startsWith(basePath)) {
                throw new FileNotFoundException("outside downloads");
            }
        } catch (Exception error) {
            throw new FileNotFoundException("bad path");
        }
        if (!target.isFile()) throw new FileNotFoundException(target.getName());
        return target;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        String name = uri == null || uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment();
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < name.length()) {
            String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                    name.substring(dot + 1).toLowerCase());
            if (type != null) return type;
        }
        return "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
