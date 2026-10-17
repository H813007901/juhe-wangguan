package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;
import java.util.ArrayList;
import java.util.Collection;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class ExplicitHotWord extends JceStruct {
    static CardItem cache_cardItem = new CardItem();
    static ArrayList<Integer> cache_sceneIDs = new ArrayList<>();
    public CardItem cardItem;
    public String copywriting;
    public int from;
    public int hotWordCategory;
    public String packageName;
    public ArrayList<Integer> sceneIDs;
    public int tabPage;
    public int type;
    public long validEndTime;
    public long validStartTime;
    public String word;

    static {
        cache_sceneIDs.add(0);
    }

    public ExplicitHotWord() {
        this.word = "";
        this.copywriting = "";
        this.packageName = "";
        this.hotWordCategory = 0;
        this.validStartTime = 0L;
        this.validEndTime = 0L;
        this.from = 0;
        this.tabPage = 0;
        this.type = 0;
        this.cardItem = null;
        this.sceneIDs = null;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.word = jceInputStream.readString(0, false);
        this.copywriting = jceInputStream.readString(1, false);
        this.packageName = jceInputStream.readString(2, false);
        this.hotWordCategory = jceInputStream.read(this.hotWordCategory, 3, false);
        this.validStartTime = jceInputStream.read(this.validStartTime, 4, false);
        this.validEndTime = jceInputStream.read(this.validEndTime, 5, false);
        this.from = jceInputStream.read(this.from, 6, false);
        this.tabPage = jceInputStream.read(this.tabPage, 7, false);
        this.type = jceInputStream.read(this.type, 8, false);
        this.cardItem = (CardItem) jceInputStream.read((JceStruct) cache_cardItem, 9, false);
        this.sceneIDs = (ArrayList) jceInputStream.read(cache_sceneIDs, 10, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        String str = this.word;
        if (str != null) {
            jceOutputStream.write(str, 0);
        }
        String str2 = this.copywriting;
        if (str2 != null) {
            jceOutputStream.write(str2, 1);
        }
        String str3 = this.packageName;
        if (str3 != null) {
            jceOutputStream.write(str3, 2);
        }
        jceOutputStream.write(this.hotWordCategory, 3);
        jceOutputStream.write(this.validStartTime, 4);
        jceOutputStream.write(this.validEndTime, 5);
        jceOutputStream.write(this.from, 6);
        jceOutputStream.write(this.tabPage, 7);
        jceOutputStream.write(this.type, 8);
        CardItem cardItem = this.cardItem;
        if (cardItem != null) {
            jceOutputStream.write((JceStruct) cardItem, 9);
        }
        ArrayList<Integer> arrayList = this.sceneIDs;
        if (arrayList != null) {
            jceOutputStream.write((Collection) arrayList, 10);
        }
    }

    public ExplicitHotWord(String str, String str2, String str3, int i, long j2, long j3, int i2, int i3, int i4, CardItem cardItem, ArrayList<Integer> arrayList) {
        this.word = str;
        this.copywriting = str2;
        this.packageName = str3;
        this.hotWordCategory = i;
        this.validStartTime = j2;
        this.validEndTime = j3;
        this.from = i2;
        this.tabPage = i3;
        this.type = i4;
        this.cardItem = cardItem;
        this.sceneIDs = arrayList;
    }
}
