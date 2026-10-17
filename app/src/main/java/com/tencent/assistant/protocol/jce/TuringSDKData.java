package com.tencent.assistant.protocol.jce;

import android.os.Parcel;
import android.os.Parcelable;
import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class TuringSDKData extends JceStruct implements Parcelable {
    public static final Parcelable.Creator<Terminal> CREATOR = new xb();
    public String ticket;

    /* compiled from: ProGuard */
    /* loaded from: classes2.dex */
    public static class xb implements Parcelable.Creator<Terminal> {
        @Override // android.os.Parcelable.Creator
        public final Terminal createFromParcel(Parcel parcel) {
            return new Terminal(parcel);
        }

        @Override // android.os.Parcelable.Creator
        public final Terminal[] newArray(int i) {
            return new Terminal[i];
        }
    }

    public TuringSDKData() {
        this.ticket = "";
    }

    @Override // android.os.Parcelable
    public int describeContents() {
        return 0;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.ticket = jceInputStream.readString(0, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        String str = this.ticket;
        if (str != null) {
            jceOutputStream.write(str, 0);
        }
    }

    @Override // android.os.Parcelable
    public void writeToParcel(Parcel parcel, int i) {
        parcel.writeString(this.ticket);
    }

    public TuringSDKData(String str) {
        this.ticket = str;
    }

    public TuringSDKData(Parcel parcel) {
        this.ticket = "";
        this.ticket = parcel.readString();
    }
}
