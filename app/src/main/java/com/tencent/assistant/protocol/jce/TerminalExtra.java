package com.tencent.assistant.protocol.jce;

import android.os.Parcel;
import android.os.Parcelable;
import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class TerminalExtra extends JceStruct implements Parcelable {
    public static final Parcelable.Creator<TerminalExtra> CREATOR = new xb();
    public String abiList;
    public short apiLevel;
    public int cpuCoresNum;
    public int cpuMaxFreq;
    public int cpuMinFreq;
    public String cpuName;
    public String fingerprint;
    public String model;
    public long ramTotalSize;
    public String romName;
    public String romVersion;
    public short storageSpeed;

    /* compiled from: ProGuard */
    /* loaded from: classes2.dex */
    public static class xb implements Parcelable.Creator<TerminalExtra> {
        @Override // android.os.Parcelable.Creator
        public final TerminalExtra createFromParcel(Parcel parcel) {
            return new TerminalExtra(parcel);
        }

        @Override // android.os.Parcelable.Creator
        public final TerminalExtra[] newArray(int i) {
            return new TerminalExtra[i];
        }
    }

    public TerminalExtra() {
        this.cpuName = "";
        this.cpuCoresNum = 0;
        this.cpuMaxFreq = 0;
        this.cpuMinFreq = 0;
        this.ramTotalSize = 0L;
        this.romName = "";
        this.romVersion = "";
        this.fingerprint = "";
        this.model = "";
        this.apiLevel = (short) 0;
        this.storageSpeed = (short) 0;
        this.abiList = "";
    }

    @Override // android.os.Parcelable
    public int describeContents() {
        return 0;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.cpuName = jceInputStream.readString(0, false);
        this.cpuCoresNum = jceInputStream.read(this.cpuCoresNum, 1, false);
        this.cpuMaxFreq = jceInputStream.read(this.cpuMaxFreq, 2, false);
        this.cpuMinFreq = jceInputStream.read(this.cpuMinFreq, 3, false);
        this.ramTotalSize = jceInputStream.read(this.ramTotalSize, 4, false);
        this.romName = jceInputStream.readString(5, false);
        this.romVersion = jceInputStream.readString(6, false);
        this.fingerprint = jceInputStream.readString(7, false);
        this.model = jceInputStream.readString(8, false);
        this.apiLevel = jceInputStream.read(this.apiLevel, 9, false);
        this.storageSpeed = jceInputStream.read(this.storageSpeed, 10, false);
        this.abiList = jceInputStream.readString(11, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        String str = this.cpuName;
        if (str != null) {
            jceOutputStream.write(str, 0);
        }
        jceOutputStream.write(this.cpuCoresNum, 1);
        jceOutputStream.write(this.cpuMaxFreq, 2);
        jceOutputStream.write(this.cpuMinFreq, 3);
        jceOutputStream.write(this.ramTotalSize, 4);
        String str2 = this.romName;
        if (str2 != null) {
            jceOutputStream.write(str2, 5);
        }
        String str3 = this.romVersion;
        if (str3 != null) {
            jceOutputStream.write(str3, 6);
        }
        String str4 = this.fingerprint;
        if (str4 != null) {
            jceOutputStream.write(str4, 7);
        }
        String str5 = this.model;
        if (str5 != null) {
            jceOutputStream.write(str5, 8);
        }
        jceOutputStream.write(this.apiLevel, 9);
        jceOutputStream.write(this.storageSpeed, 10);
        String str6 = this.abiList;
        if (str6 != null) {
            jceOutputStream.write(str6, 11);
        }
    }

    @Override // android.os.Parcelable
    public void writeToParcel(Parcel parcel, int i) {
        parcel.writeString(this.cpuName);
        parcel.writeInt(this.cpuCoresNum);
        parcel.writeInt(this.cpuMaxFreq);
        parcel.writeInt(this.cpuMinFreq);
        parcel.writeLong(this.ramTotalSize);
        parcel.writeString(this.romName);
        parcel.writeString(this.romVersion);
        parcel.writeString(this.fingerprint);
        parcel.writeString(this.model);
        parcel.writeInt(this.apiLevel);
        parcel.writeInt(this.storageSpeed);
        parcel.writeString(this.abiList);
    }

    public TerminalExtra(String str, int i, int i2, int i3, long j2, String str2, String str3, String str4, String str5, short s, short s2, String str6) {
        this.cpuName = str;
        this.cpuCoresNum = i;
        this.cpuMaxFreq = i2;
        this.cpuMinFreq = i3;
        this.ramTotalSize = j2;
        this.romName = str2;
        this.romVersion = str3;
        this.fingerprint = str4;
        this.model = str5;
        this.apiLevel = s;
        this.storageSpeed = s2;
        this.abiList = str6;
    }

    public TerminalExtra(Parcel parcel) {
        this.cpuName = "";
        this.cpuCoresNum = 0;
        this.cpuMaxFreq = 0;
        this.cpuMinFreq = 0;
        this.ramTotalSize = 0L;
        this.romName = "";
        this.romVersion = "";
        this.fingerprint = "";
        this.model = "";
        this.apiLevel = (short) 0;
        this.storageSpeed = (short) 0;
        this.abiList = "";
        this.cpuName = parcel.readString();
        this.cpuCoresNum = parcel.readInt();
        this.cpuMaxFreq = parcel.readInt();
        this.cpuMinFreq = parcel.readInt();
        this.ramTotalSize = parcel.readLong();
        this.romName = parcel.readString();
        this.romVersion = parcel.readString();
        this.fingerprint = parcel.readString();
        this.model = parcel.readString();
        this.apiLevel = (short) parcel.readInt();
        this.storageSpeed = (short) parcel.readInt();
        this.abiList = parcel.readString();
    }
}
