package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class PkgReqHead extends JceStruct {
    static Ticket cache_csTicket = new Ticket();
    public String cguid;
    public String checkSum;
    public Ticket csTicket;
    public String dictVersion;
    public byte encryptWithPack;
    public int requestId;

    public PkgReqHead() {
        this.requestId = 0;
        this.csTicket = null;
        this.encryptWithPack = (byte) 0;
        this.cguid = "";
        this.checkSum = "";
        this.dictVersion = "";
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.requestId = jceInputStream.read(this.requestId, 0, true);
        this.csTicket = (Ticket) jceInputStream.read((JceStruct) cache_csTicket, 1, false);
        this.encryptWithPack = jceInputStream.read(this.encryptWithPack, 2, false);
        this.cguid = jceInputStream.readString(3, false);
        this.checkSum = jceInputStream.readString(4, false);
        this.dictVersion = jceInputStream.readString(5, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.requestId, 0);
        Ticket ticket = this.csTicket;
        if (ticket != null) {
            jceOutputStream.write((JceStruct) ticket, 1);
        }
        jceOutputStream.write(this.encryptWithPack, 2);
        String str = this.cguid;
        if (str != null) {
            jceOutputStream.write(str, 3);
        }
        String str2 = this.checkSum;
        if (str2 != null) {
            jceOutputStream.write(str2, 4);
        }
        String str3 = this.dictVersion;
        if (str3 != null) {
            jceOutputStream.write(str3, 5);
        }
    }

    public PkgReqHead(int i, Ticket ticket, byte b2, String str, String str2, String str3) {
        this.requestId = i;
        this.csTicket = ticket;
        this.encryptWithPack = b2;
        this.cguid = str;
        this.checkSum = str2;
        this.dictVersion = str3;
    }
}
