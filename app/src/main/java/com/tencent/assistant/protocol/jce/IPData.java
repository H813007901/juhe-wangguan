package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;
import java.util.ArrayList;
import java.util.Collection;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class IPData extends JceStruct {
    static ArrayList<IPDataAddress> cache_addrList = new ArrayList<>();
    static int cache_type;
    public ArrayList<IPDataAddress> addrList;
    public long expirationTime;
    public int type;

    static {
        cache_addrList.add(new IPDataAddress());
    }

    public IPData() {
        this.type = 0;
        this.addrList = null;
        this.expirationTime = 0L;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.type = jceInputStream.read(this.type, 0, true);
        this.addrList = (ArrayList) jceInputStream.read(cache_addrList, 1, true);
        this.expirationTime = jceInputStream.read(this.expirationTime, 2, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.type, 0);
        jceOutputStream.write((Collection) this.addrList, 1);
        jceOutputStream.write(this.expirationTime, 2);
    }

    public IPData(int i, ArrayList<IPDataAddress> arrayList, long j2) {
        this.type = i;
        this.addrList = arrayList;
        this.expirationTime = j2;
    }
}
