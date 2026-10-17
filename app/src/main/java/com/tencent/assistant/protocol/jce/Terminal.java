package com.tencent.assistant.protocol.jce;

import android.os.Parcel;
import android.os.Parcelable;
import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class Terminal extends JceStruct implements Parcelable {
    public static final Parcelable.Creator<Terminal> CREATOR = new xb();
    public String androidId;
    public String androidIdSdCard;
    public String imei;
    public String imei2;
    public String imsi;
    public String imsi2;
    public String macAdress;
    public String mid;

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

    public Terminal() {
        this.imei = "";
        this.macAdress = "";
        this.androidId = "";
        this.androidIdSdCard = "";
        this.imsi = "";
        this.imsi2 = "";
        this.imei2 = "";
        this.mid = "";
    }

    @Override // android.os.Parcelable
    public int describeContents() {
        return 0;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.imei = jceInputStream.readString(0, false);
        this.macAdress = jceInputStream.readString(1, false);
        this.androidId = jceInputStream.readString(2, false);
        this.androidIdSdCard = jceInputStream.readString(3, false);
        this.imsi = jceInputStream.readString(4, false);
        this.imsi2 = jceInputStream.readString(5, false);
        this.imei2 = jceInputStream.readString(6, false);
        this.mid = jceInputStream.readString(7, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        String str = this.imei;
        if (str != null) {
            jceOutputStream.write(str, 0);
        }
        String str2 = this.macAdress;
        if (str2 != null) {
            jceOutputStream.write(str2, 1);
        }
        String str3 = this.androidId;
        if (str3 != null) {
            jceOutputStream.write(str3, 2);
        }
        String str4 = this.androidIdSdCard;
        if (str4 != null) {
            jceOutputStream.write(str4, 3);
        }
        String str5 = this.imsi;
        if (str5 != null) {
            jceOutputStream.write(str5, 4);
        }
        String str6 = this.imsi2;
        if (str6 != null) {
            jceOutputStream.write(str6, 5);
        }
        String str7 = this.imei2;
        if (str7 != null) {
            jceOutputStream.write(str7, 6);
        }
        String str8 = this.mid;
        if (str8 != null) {
            jceOutputStream.write(str8, 7);
        }
    }

    @Override // android.os.Parcelable
    public void writeToParcel(Parcel parcel, int i) {
        parcel.writeString(this.imei);
        parcel.writeString(this.macAdress);
        parcel.writeString(this.androidId);
        parcel.writeString(this.androidIdSdCard);
        parcel.writeString(this.imsi);
        parcel.writeString(this.imsi2);
        parcel.writeString(this.imei2);
        parcel.writeString(this.mid);
    }

    public Terminal(String str, String str2, String str3, String str4, String str5, String str6, String str7, String str8) {
        this.imei = str;
        this.macAdress = str2;
        this.androidId = str3;
        this.androidIdSdCard = str4;
        this.imsi = str5;
        this.imsi2 = str6;
        this.imei2 = str7;
        this.mid = str8;
    }

    public Terminal(Parcel parcel) {
        this.imei = "";
        this.macAdress = "";
        this.androidId = "";
        this.androidIdSdCard = "";
        this.imsi = "";
        this.imsi2 = "";
        this.imei2 = "";
        this.mid = "";
        this.imei = parcel.readString();
        this.macAdress = parcel.readString();
        this.androidId = parcel.readString();
        this.androidIdSdCard = parcel.readString();
        this.imsi = parcel.readString();
        this.imsi2 = parcel.readString();
        this.imei2 = parcel.readString();
        this.mid = parcel.readString();
    }
}
