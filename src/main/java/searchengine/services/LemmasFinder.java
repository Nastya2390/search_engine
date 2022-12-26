package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.apache.lucene.morphology.LuceneMorphology;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RequiredArgsConstructor
public class LemmasFinder {
    private static final String[] particlesNames = new String[]{"МЕЖД", "ПРЕДЛ", "СОЮЗ", "МС", "ЧАСТ"};
    private final LuceneMorphology luceneMorphology;

    public Map<String, Integer> getTextLemmas(String text) {
        Map<String, Integer> lemmas = new HashMap<>();
        String[] words = getRussianWords(text);

        for (String word : words) {
            List<String> morphInfos = luceneMorphology.getMorphInfo(word);
            if (anyMorphInfoBelongToParticle(morphInfos)) {
                continue;
            }
            List<String> baseForms = luceneMorphology.getNormalForms(word);
            if (baseForms.isEmpty()) {
                continue;
            }
            String normalWord = baseForms.get(0);
            if(normalWord.length() < 2) {
                continue;
            }
            if (lemmas.containsKey(normalWord)) {
                lemmas.put(normalWord, lemmas.get(normalWord) + 1);
            } else {
                lemmas.put(normalWord, 1);
            }
        }

        return lemmas;
    }

    public String deleteHtmlTags(String text) {
        return text.replaceAll("<!DOCTYPE>", " ")
                .replaceAll("<!-- -->", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("</[^>]+>", " ");
    }

    private String[] getRussianWords(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("([^а-я\\s])", " ")
                .trim()
                .split("\\s+");
    }

    private Boolean anyMorphInfoBelongToParticle(List<String> morphInfos) {
        return morphInfos.stream().anyMatch(this::isParticleMorphInfo);
    }

    private Boolean isParticleMorphInfo(String morphInfo) {
        for (String particle : particlesNames) {
            if (morphInfo.toUpperCase().contains(particle)) {
                return true;
            }
        }
        return false;
    }

}
