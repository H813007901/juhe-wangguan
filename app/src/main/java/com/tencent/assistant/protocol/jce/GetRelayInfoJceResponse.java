package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class GetRelayInfoJceResponse extends JceStruct {
    static RelayInfoJce cache_relayInfo = new RelayInfoJce();
    public String msg;
    public RelayInfoJce relayInfo;
    public int ret;

    public GetRelayInfoJceResponse() {
        this.ret = 0;
        this.msg = "";
        this.relayInfo = null;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.ret = jceInputStream.read(this.ret, 0, true);
        this.msg = jceInputStream.readString(1, false);
        this.relayInfo = (RelayInfoJce) jceInputStream.read((JceStruct) cache_relayInfo, 2, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.ret, 0);
        String str = this.msg;
        if (str != null) {
            jceOutputStream.write(str, 1);
        }
        RelayInfoJce relayInfoJce = this.relayInfo;
        if (relayInfoJce != null) {
            jceOutputStream.write((JceStruct) relayInfoJce, 2);
        }
    }

    public GetRelayInfoJceResponse(int i, String str, RelayInfoJce relayInfoJce) {
        this.ret = i;
        this.msg = str;
        this.relayInfo = relayInfoJce;
    }
}
