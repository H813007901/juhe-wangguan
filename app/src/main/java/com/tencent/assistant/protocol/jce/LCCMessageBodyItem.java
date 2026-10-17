package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class LCCMessageBodyItem extends JceStruct {
    static LCMessageBodyBase cache_data = new LCMessageBodyBase();
    public LCMessageBodyBase data;
    public long seqid;
    public int seqtype;

    public LCCMessageBodyItem() {
        this.seqtype = 0;
        this.seqid = 0L;
        this.data = null;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.seqtype = jceInputStream.read(this.seqtype, 0, true);
        this.seqid = jceInputStream.read(this.seqid, 1, true);
        this.data = (LCMessageBodyBase) jceInputStream.read((JceStruct) cache_data, 2, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.seqtype, 0);
        jceOutputStream.write(this.seqid, 1);
        LCMessageBodyBase lCMessageBodyBase = this.data;
        if (lCMessageBodyBase != null) {
            jceOutputStream.write((JceStruct) lCMessageBodyBase, 2);
        }
    }

    public LCCMessageBodyItem(int i, long j2, LCMessageBodyBase lCMessageBodyBase) {
        this.seqtype = i;
        this.seqid = j2;
        this.data = lCMessageBodyBase;
    }
}
