package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;
import java.util.ArrayList;
import java.util.Collection;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class GetExplicitHotWordsResponse extends JceStruct {
    static ArrayList<ExplicitHotWord> cache_explicitHotWords = new ArrayList<>();
    public ArrayList<ExplicitHotWord> explicitHotWords;
    public int ret;
    public String searchPreId;
    public String serverIP;
    public long validTime;
    public long version;

    static {
        cache_explicitHotWords.add(new ExplicitHotWord());
    }

    public GetExplicitHotWordsResponse() {
        this.ret = 0;
        this.version = 0L;
        this.validTime = 0L;
        this.explicitHotWords = null;
        this.serverIP = "";
        this.searchPreId = "";
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.ret = jceInputStream.read(this.ret, 0, false);
        this.version = jceInputStream.read(this.version, 1, false);
        this.validTime = jceInputStream.read(this.validTime, 2, false);
        this.explicitHotWords = (ArrayList) jceInputStream.read(cache_explicitHotWords, 3, false);
        this.serverIP = jceInputStream.readString(4, false);
        this.searchPreId = jceInputStream.readString(5, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.ret, 0);
        jceOutputStream.write(this.version, 1);
        jceOutputStream.write(this.validTime, 2);
        ArrayList<ExplicitHotWord> arrayList = this.explicitHotWords;
        if (arrayList != null) {
            jceOutputStream.write((Collection) arrayList, 3);
        }
        String str = this.serverIP;
        if (str != null) {
            jceOutputStream.write(str, 4);
        }
        String str2 = this.searchPreId;
        if (str2 != null) {
            jceOutputStream.write(str2, 5);
        }
    }

    public GetExplicitHotWordsResponse(int i, long j2, long j3, ArrayList<ExplicitHotWord> arrayList, String str, String str2) {
        this.ret = i;
        this.version = j2;
        this.validTime = j3;
        this.explicitHotWords = arrayList;
        this.serverIP = str;
        this.searchPreId = str2;
    }
}
