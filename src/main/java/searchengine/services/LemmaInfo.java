package searchengine.services;

import lombok.Data;
import searchengine.model.Lemma;

import java.util.List;

@Data
public class LemmaInfo implements Comparable<LemmaInfo> {
    private List<Lemma> lemmaList;
    private int commonFrequency;

    public LemmaInfo(List<Lemma> lemmaList, int commonFrequency) {
        this.lemmaList = lemmaList;
        this.commonFrequency = commonFrequency;
    }

    @Override
    public int compareTo(LemmaInfo o) {
        return this.getCommonFrequency() - o.getCommonFrequency();
    }

}
