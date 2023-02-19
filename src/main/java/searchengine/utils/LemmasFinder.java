package searchengine.utils;

import lombok.RequiredArgsConstructor;
import org.apache.lucene.morphology.LuceneMorphology;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class LemmasFinder {
    private static final String[] particlesNamesRus = new String[]{"МЕЖД", "ПРЕДЛ", "СОЮЗ", "МС", "ЧАСТ"};
    private static final String[] particlesNamesEng = new String[]{"PREP", "PART", "CONJ", "ARTICLE"};
    private final LuceneMorphology luceneMorphologyRus;
    private final LuceneMorphology luceneMorphologyEng;

    public Map<String, Integer> getTextRusEngLemmas(String text) {
        String[] words = getRussianWords(text);
        Map<String, Integer> russianLemmas = getTextLemmas(words, luceneMorphologyRus, particlesNamesRus);
        String[] engWords = getEnglishWords(text);
        Map<String, Integer> englishLemmas = getTextLemmas(engWords, luceneMorphologyEng, particlesNamesEng);
        russianLemmas.putAll(englishLemmas);
        return russianLemmas;
    }

    public Map<String, Integer> getTextLemmas(String[] words,
                                              LuceneMorphology luceneMorphology, String[] particlesNames) {
        Map<String, Integer> lemmas = new HashMap<>();
        if (isEmptyArray(words)) return lemmas;

        for (String word : words) {
            List<String> morphInfos = luceneMorphology.getMorphInfo(word);
            if (anyMorphInfoBelongToParticle(morphInfos, particlesNames)) {
                continue;
            }
            List<String> baseForms = luceneMorphology.getNormalForms(word);
            if (baseForms.isEmpty()) {
                continue;
            }
            String normalWord = baseForms.get(0);
            if (normalWord.length() < 3) {
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

    public boolean isWordRelatedToBaseForm(String word, String baseForm) {
        word = word.toLowerCase(Locale.ROOT);
        if(word.equals(baseForm)) return true;
        String[] rusWord = getRussianWords(word);
        String[] engWord = getEnglishWords(word);
        List<String> baseForms = new ArrayList<>();
        if(!(rusWord.length == 1 && rusWord[0].isEmpty())) {
            baseForms = luceneMorphologyRus.getNormalForms(rusWord[0]);
        } else if(!(engWord.length == 1 && engWord[0].isEmpty())) {
            baseForms = luceneMorphologyEng.getNormalForms(engWord[0]);
        }
        return baseForms.contains(baseForm);
    }

    public String deleteHtmlTags(String text) {
        return text.replaceAll("\\r\\n", " ")
                .replaceAll("\\n", " ")
                .replaceAll("<style.*?</style>", " ")
                .replaceAll("<script.*?</script>", " ")
                .replaceAll("<noscript.*?</noscript>", " ")
                .replaceAll("<textarea.*?</textarea>", " ")
                .replaceAll("<li><a.*?</a></li>", " ")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("<!--.*?-->", " ")
                .replaceAll("&nbsp;", " ");
    }

    private boolean isEmptyArray(String[] words) {
        return words.length == 1 && words[0].equals("");
    }

    private String[] getRussianWords(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("([^а-яА-ЯёЁ\\s])", " ")
                .trim()
                .split("\\s+");
    }

    private String[] getEnglishWords(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("([^a-zA-Z\\s])", " ")
                .trim()
                .split("\\s+");
    }

    private Boolean anyMorphInfoBelongToParticle(List<String> morphInfos, String[] particlesNames) {
        return morphInfos.stream().anyMatch(x -> isParticleMorphInfo(x, particlesNames));
    }

    private Boolean isParticleMorphInfo(String morphInfo, String[] particlesNames) {
        for (String particle : particlesNames) {
            if (morphInfo.toUpperCase().contains(particle)) {
                return true;
            }
        }
        return false;
    }

}
