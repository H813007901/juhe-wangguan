package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class KeepAliveMsgHead extends JceStruct {
    static Ticket cache_csTicket = new Ticket();
    public String checkSum;
    public Ticket csTicket;
    public String dictVersion;
    public byte encryptWithPack;
    public int requestId;
    public int ret;

    public KeepAliveMsgHead() {
        this.requestId = 0;
        this.csTicket = null;
        this.encryptWithPack = (byte) 0;
        this.checkSum = "";
        this.dictVersion = "";
        this.ret = 0;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.requestId = jceInputStream.read(this.requestId, 0, true);
        this.csTicket = (Ticket) jceInputStream.read((JceStruct) cache_csTicket, 1, true);
        this.encryptWithPack = jceInputStream.read(this.encryptWithPack, 2, true);
        this.checkSum = jceInputStream.readString(3, true);
        this.dictVersion = jceInputStream.readString(4, false);
        this.ret = jceInputStream.read(this.ret, 5, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.requestId, 0);
        jceOutputStream.write((JceStruct) this.csTicket, 1);
        jceOutputStream.write(this.encryptWithPack, 2);
        jceOutputStream.write(this.checkSum, 3);
        String str = this.dictVersion;
        if (str != null) {
            jceOutputStream.write(str, 4);
        }
        jceOutputStream.write(this.ret, 5);
    }

    public KeepAliveMsgHead(int i, Ticket ticket, byte b2, String str, String str2, int i2) {
        this.requestId = i;
        this.csTicket = ticket;
        this.encryptWithPack = b2;
        this.checkSum = str;
        this.dictVersion = str2;
        this.ret = i2;
    }
}
