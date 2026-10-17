package com.tencent.assistant.protocol.jce;

import android.os.Parcel;
import android.os.Parcelable;
import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class ReqHeadExternal extends JceStruct implements Parcelable {
    public static final Parcelable.Creator<ReqHeadExternal> CREATOR = new xb();
    static byte[] cache_content;
    public byte[] content;
    public int id;

    /* compiled from: ProGuard */
    /* loaded from: classes2.dex */
    public static class xb implements Parcelable.Creator<ReqHeadExternal> {
        @Override // android.os.Parcelable.Creator
        public final ReqHeadExternal createFromParcel(Parcel parcel) {
            return new ReqHeadExternal(parcel);
        }

        @Override // android.os.Parcelable.Creator
        public final ReqHeadExternal[] newArray(int i) {
            return new ReqHeadExternal[i];
        }
    }

    static {
        cache_content = new byte[]{0};
    }

    public ReqHeadExternal() {
        this.id = 0;
        this.content = null;
    }

    @Override // android.os.Parcelable
    public int describeContents() {
        return 0;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.id = jceInputStream.read(this.id, 0, true);
        this.content = jceInputStream.read(cache_content, 1, true);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.id, 0);
        jceOutputStream.write(this.content, 1);
    }

    @Override // android.os.Parcelable
    public void writeToParcel(Parcel parcel, int i) {
        parcel.writeInt(this.id);
        parcel.writeByteArray(this.content);
    }

    public ReqHeadExternal(int i, byte[] bArr) {
        this.id = i;
        this.content = bArr;
    }

    public ReqHeadExternal(Parcel parcel) {
        this.id = 0;
        this.content = null;
        this.id = parcel.readInt();
        this.content = parcel.createByteArray();
    }
}
