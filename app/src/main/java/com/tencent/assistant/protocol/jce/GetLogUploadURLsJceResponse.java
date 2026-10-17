package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;
import java.util.ArrayList;
import java.util.Collection;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class GetLogUploadURLsJceResponse extends JceStruct {
    static ArrayList<LogDownloadInfo> cache_cloud_bug_urls = new ArrayList<>();
    static ArrayList<LogDownloadInfo> cache_pc_bug_urls;
    public ArrayList<LogDownloadInfo> cloud_bug_urls;
    public String msg;
    public ArrayList<LogDownloadInfo> pc_bug_urls;
    public int ret;

    static {
        cache_cloud_bug_urls.add(new LogDownloadInfo());
        cache_pc_bug_urls = new ArrayList<>();
        cache_pc_bug_urls.add(new LogDownloadInfo());
    }

    public GetLogUploadURLsJceResponse() {
        this.ret = 0;
        this.msg = "";
        this.cloud_bug_urls = null;
        this.pc_bug_urls = null;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.ret = jceInputStream.read(this.ret, 0, true);
        this.msg = jceInputStream.readString(1, false);
        this.cloud_bug_urls = (ArrayList) jceInputStream.read(cache_cloud_bug_urls, 2, false);
        this.pc_bug_urls = (ArrayList) jceInputStream.read(cache_pc_bug_urls, 3, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.ret, 0);
        String str = this.msg;
        if (str != null) {
            jceOutputStream.write(str, 1);
        }
        ArrayList<LogDownloadInfo> arrayList = this.cloud_bug_urls;
        if (arrayList != null) {
            jceOutputStream.write((Collection) arrayList, 2);
        }
        ArrayList<LogDownloadInfo> arrayList2 = this.pc_bug_urls;
        if (arrayList2 != null) {
            jceOutputStream.write((Collection) arrayList2, 3);
        }
    }

    public GetLogUploadURLsJceResponse(int i, String str, ArrayList<LogDownloadInfo> arrayList, ArrayList<LogDownloadInfo> arrayList2) {
        this.ret = i;
        this.msg = str;
        this.cloud_bug_urls = arrayList;
        this.pc_bug_urls = arrayList2;
    }
}
