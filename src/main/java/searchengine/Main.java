package searchengine;

import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import searchengine.services.LemmasFinder;

import java.io.IOException;
import java.util.Map;

public class Main {

    public static void main(String[] args) throws IOException {
        LuceneMorphology luceneMorph = new RussianLuceneMorphology();
        LemmasFinder lemmasFinder = new LemmasFinder(luceneMorph);

        Map<String, Integer> lemmas = lemmasFinder.getTextLemmas("его они апр чт ть ч т не тот мы сный вть");
        for (String lemma : lemmas.keySet()) {
            System.out.println(lemma + " - " + lemmas.get(lemma));
        }

//        String text = lemmasFinder.deleteHtmlTags("</svg> <h4 class=\"tooltip-info__title\"> Профессия </h4> <p class=\"tooltip-info__desc\">Состоит из&nbsp;нескольких курсов, воркшопов и&nbsp;практикумов. Вы сможете полностью освоить новую профессию с&nbsp;нуля, собрать портфолио, подготовить резюме и&nbsp;найти работу.</p><h4 class=\"tooltip-info__title\"> Курс </h4> <p class=\"tooltip-info__desc\">Состоит из&nbsp;нескольких модулей с&nbsp;видеоматериалами и&nbsp;практикой. Вы сможете изучить азы профессии, освоить конкретный навык или&nbsp;инструмент и&nbsp;добавить их в&nbsp;своё портфолио.</p>");
//        System.out.println();
    }
}
