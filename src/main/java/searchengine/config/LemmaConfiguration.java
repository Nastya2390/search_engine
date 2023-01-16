package searchengine.config;

import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.english.EnglishLuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
public class LemmaConfiguration {

    @Bean
    public LuceneMorphology luceneMorphologyRus() throws IOException {
        return new RussianLuceneMorphology();
    }

    @Bean
    public LuceneMorphology luceneMorphologyEng() throws IOException {
        return new EnglishLuceneMorphology();
    }

}
