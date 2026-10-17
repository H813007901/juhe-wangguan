package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceDisplayer;
import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;
import com.qq.taf.jce.JceUtil;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class AuthResponse extends JceStruct implements Cloneable {
    static final /* synthetic */ boolean $assertionsDisabled = false;
    static Ticket cache_csTicket = new Ticket();
    public Ticket csTicket;
    public long cstTimestamp;
    public int randNum;
    public int ret;
    public String rsaClientAuthId;

    public AuthResponse() {
        this.ret = 0;
        this.csTicket = null;
        this.randNum = 0;
        this.cstTimestamp = 0L;
        this.rsaClientAuthId = "";
    }

    public String className() {
        return "jce.AuthResponse";
    }

    public Object clone() {
        try {
            return super.clone();
        } catch (CloneNotSupportedException unused) {
            return null;
        }
    }

    @Override // com.qq.taf.jce.JceStruct
    public void display(StringBuilder sb, int i) {
        JceDisplayer jceDisplayer = new JceDisplayer(sb, i);
        jceDisplayer.display(this.ret, "ret");
        jceDisplayer.display((JceStruct) this.csTicket, "csTicket");
        jceDisplayer.display(this.randNum, "randNum");
        jceDisplayer.display(this.cstTimestamp, "cstTimestamp");
        jceDisplayer.display(this.rsaClientAuthId, "rsaClientAuthId");
    }

    @Override // com.qq.taf.jce.JceStruct
    public void displaySimple(StringBuilder sb, int i) {
        JceDisplayer jceDisplayer = new JceDisplayer(sb, i);
        jceDisplayer.displaySimple(this.ret, true);
        jceDisplayer.displaySimple((JceStruct) this.csTicket, true);
        jceDisplayer.displaySimple(this.randNum, true);
        jceDisplayer.displaySimple(this.cstTimestamp, true);
        jceDisplayer.displaySimple(this.rsaClientAuthId, false);
    }

    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        AuthResponse authResponse = (AuthResponse) obj;
        if (!JceUtil.equals(this.ret, authResponse.ret) || !JceUtil.equals(this.csTicket, authResponse.csTicket) || !JceUtil.equals(this.randNum, authResponse.randNum) || !JceUtil.equals(this.cstTimestamp, authResponse.cstTimestamp) || !JceUtil.equals(this.rsaClientAuthId, authResponse.rsaClientAuthId)) {
            return false;
        }
        return true;
    }

    public String fullClassName() {
        return "com.tencent.assistant.protocol.jce.AuthResponse";
    }

    public int hashCode() {
        try {
            throw new Exception("Need define key first!");
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.ret = jceInputStream.read(this.ret, 0, true);
        this.csTicket = (Ticket) jceInputStream.read((JceStruct) cache_csTicket, 1, true);
        this.randNum = jceInputStream.read(this.randNum, 2, false);
        this.cstTimestamp = jceInputStream.read(this.cstTimestamp, 3, false);
        this.rsaClientAuthId = jceInputStream.readString(4, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.ret, 0);
        jceOutputStream.write((JceStruct) this.csTicket, 1);
        jceOutputStream.write(this.randNum, 2);
        jceOutputStream.write(this.cstTimestamp, 3);
        String str = this.rsaClientAuthId;
        if (str != null) {
            jceOutputStream.write(str, 4);
        }
    }

    public AuthResponse(int i, Ticket ticket, int i2, long j2, String str) {
        this.ret = i;
        this.csTicket = ticket;
        this.randNum = i2;
        this.cstTimestamp = j2;
        this.rsaClientAuthId = str;
    }
}
