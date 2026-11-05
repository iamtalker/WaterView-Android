package kr.iamtalker.waterview;

import android.os.ParcelFileDescriptor;

oneway interface IPdfRenderer {
    void open(in ParcelFileDescriptor document, in ParcelFileDescriptor answer);
    void pageSize(int page, in ParcelFileDescriptor answer);
    void render(int page, int width, int height, int flags, in ParcelFileDescriptor answer);
}
