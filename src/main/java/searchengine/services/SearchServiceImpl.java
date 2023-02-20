package searchengine.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import searchengine.dto.search.LemmaInfo;
import searchengine.dto.search.SearchData;
import searchengine.dto.search.SearchRequestParams;
import searchengine.dto.search.SearchResponse;
import searchengine.model.Index;
import searchengine.model.Lemma;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.repositories.IndexRepository;
import searchengine.repositories.LemmaRepository;
import searchengine.repositories.SiteRepository;
import searchengine.utils.LemmasFinder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService {

    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final SiteRepository siteRepository;
    private final LemmasFinder lemmasFinder;
    private final int OFFSET = 60;
    private final int SNIPPET_LENGTH = 250;
    private final double LEMMA_FREQUENCY_COEFFICIENT = 0.8;

    @Override
    public SearchResponse search(SearchRequestParams params) {
        log.debug("search enter");
        if (params.getQuery().equals(""))
            return new SearchResponse(true, 0, Collections.emptyList());
        Map<String, LemmaInfo> searchInfo = getLemmaFrequenciesInfo(params);
        if (searchInfo.isEmpty())
            return new SearchResponse(true, 0, Collections.emptyList());
        List<Lemma> requestLemmasList = getRequestLemmasList(searchInfo);
        searchInfo = excludeFrequentlyUsedLemma(searchInfo, params.getSite());
        if (searchInfo.isEmpty())
            return new SearchResponse(true, 0, Collections.emptyList());
        searchInfo = sortLemmasByFrequency(searchInfo);
        List<Page> foundPages = getSearchResultPages(searchInfo);
        if (foundPages.isEmpty())
            return new SearchResponse(true, 0, Collections.emptyList());
        Map<Page, Double> pagesRelevance = computePagesRelativeRelevance(foundPages, requestLemmasList);
        List<SearchData> data = fillResponseDataList(pagesRelevance, requestLemmasList, params);
        return new SearchResponse(true, pagesRelevance.size(), data);
    }

    private List<Lemma> getRequestLemmasList(Map<String, LemmaInfo> searchInfo) {
        log.debug("getRequestLemmasList enter");
        return searchInfo.values().stream()
                .map(LemmaInfo::getLemmaList).flatMap(Collection::stream).collect(Collectors.toList());
    }

    private Map<String, LemmaInfo> getLemmaFrequenciesInfo(SearchRequestParams params) {
        log.debug("getLemmaFrequenciesInfo enter");
        String siteName = params.getSite();
        Map<String, LemmaInfo> searchInfo = new HashMap<>();
        Map<String, Integer> requestLemmas = lemmasFinder.getTextRusEngLemmas(params.getQuery());
        for (String lemma : requestLemmas.keySet()) {
            Optional<List<Lemma>> lemmaListOpt = lemmaRepository.getLemmaByLemma(lemma);
            if (!lemmaListOpt.isPresent() || lemmaListOpt.get().isEmpty()) continue;
            List<Lemma> lemmaList = lemmaListOpt.get();
            if (!siteName.equals("all")) {
                lemmaList = getSpecifiedSiteLemmas(lemmaList, siteName);
            }
            if (lemmaList.isEmpty())
                return Collections.emptyMap();
            int lemmaFrequency = lemmaList.stream().map(Lemma::getFrequency).reduce(Integer::sum).get();
            searchInfo.put(lemma, new LemmaInfo(lemmaList, lemmaFrequency));
        }
        return searchInfo;
    }

    private List<Lemma> getSpecifiedSiteLemmas(List<Lemma> lemmaList, String site) {
        log.debug("getSpecifiedSiteLemmas enter");
        return lemmaList.stream()
                .filter(x -> x.getSite().getUrl().equals(site)).collect(Collectors.toList());
    }

    private Map<String, LemmaInfo> excludeFrequentlyUsedLemma(Map<String, LemmaInfo> lemmas, String site) {
        log.debug("excludeFrequentlyUsedLemma enter");
        List<Site> siteList = siteRepository.findAll();
        if (siteList.isEmpty()) return Collections.emptyMap();
        long allSearchPagesCount;
        if (site.equals("all")) {
            allSearchPagesCount = siteList.stream().map(x -> x.getPages().size()).reduce(Integer::sum).get();
        } else {
            List<Site> filteredSiteList = siteList.stream().filter(x -> x.getUrl().equals(site)).collect(Collectors.toList());
            if (filteredSiteList.isEmpty()) return Collections.emptyMap();
            allSearchPagesCount = Math.round(filteredSiteList.iterator().next().getPages().size());
        }
        Map<String, LemmaInfo> result = new HashMap<>();
        for (String lemma : lemmas.keySet()) {
            if (lemmas.get(lemma).getCommonFrequency() < allSearchPagesCount * LEMMA_FREQUENCY_COEFFICIENT)
                result.put(lemma, lemmas.get(lemma));
        }
        return result;
    }

    private Map<String, LemmaInfo> sortLemmasByFrequency(Map<String, LemmaInfo> searchInfo) {
        log.debug("sortLemmasByFrequency enter");
        return searchInfo.entrySet().stream()
                .sorted(Map.Entry.comparingByValue()).collect(Collectors.toMap(Map.Entry::getKey,
                        Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));
    }

    private List<Page> getSearchResultPages(Map<String, LemmaInfo> searchInfo) {
        log.debug("getSearchResultPages enter");
        Map<Page, Boolean> searchResult = new HashMap<>();
        boolean hasInitialFill = false;
        for (LemmaInfo lemmaInfo : searchInfo.values()) {
            List<Lemma> lemmaList = lemmaInfo.getLemmaList();
            if (hasInitialFill) {
                markNotSuitablePagesInSearchResult(lemmaList, searchResult);
            } else {
                initialSearchResultFill(lemmaList, searchResult);
                hasInitialFill = true;
            }
        }
        return excludeIncorrectPagesFromResult(searchResult);
    }

    private void initialSearchResultFill(List<Lemma> lemmaList, Map<Page, Boolean> searchResult) {
        log.debug("initialSearchResultFill enter");
        for (Lemma lemma : lemmaList) {
            Optional<List<Index>> indexListOpt = indexRepository.getIndexByLemma(lemma);
            if (!indexListOpt.isPresent() || indexListOpt.get().size() == 0) return;
            List<Page> pageList = indexListOpt.get().stream().map(Index::getPage).collect(Collectors.toList());
            for (Page page : pageList) {
                searchResult.put(page, true);
            }
        }
    }

    private void markNotSuitablePagesInSearchResult(List<Lemma> lemmaList, Map<Page, Boolean> searchResult) {
        log.debug("markNotSuitablePagesInSearchResult enter");
        for (Page page : searchResult.keySet()) {
            boolean isSuitablePage = isPageSuitableForSearchResult(lemmaList, page);
            if (!isSuitablePage) {
                searchResult.put(page, false);
            }
        }
    }

    private boolean isPageSuitableForSearchResult(List<Lemma> lemmaList, Page page) {
        log.debug("isPageSuitableForSearchResult enter");
        for (Lemma lemma : lemmaList) {
            Optional<List<Index>> indexListOpt = indexRepository.getIndexByPageIdAndLemmaId(page.getId(), lemma.getId());
            if (indexListOpt.isPresent() && indexListOpt.get().size() > 0) {
                return true;
            }
        }
        return false;
    }

    private List<Page> excludeIncorrectPagesFromResult(Map<Page, Boolean> searchResult) {
        log.debug("excludeIncorrectPagesFromResult enter");
        List<Page> foundPages = new ArrayList<>();
        for (Page page : searchResult.keySet()) {
            if (searchResult.get(page)) {
                foundPages.add(page);
            }
        }
        return foundPages;
    }

    private Map<Page, Double> computePagesRelativeRelevance(List<Page> foundPages, List<Lemma> requestLemmas) {
        log.debug("computePagesRelativeRelevance enter");
        Map<Page, Double> pagesRelevance = new LinkedHashMap<>();
        for (Page page : foundPages) {
            double pageRelevance = computePageRelevance(page, requestLemmas);
            pagesRelevance.put(page, pageRelevance);
        }
        if (pagesRelevance.size() == 0) return Collections.emptyMap();
        double maxPageRelevance = pagesRelevance.values().stream().max(Double::compare).get();
        pagesRelevance.replaceAll((page, relevance) -> pagesRelevance.get(page) / maxPageRelevance);
        return pagesRelevance.entrySet().stream()
                .sorted(Collections.reverseOrder(Map.Entry.comparingByValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));
    }

    private double computePageRelevance(Page page, List<Lemma> requestLemmas) {
        log.debug("computePageRelevance enter");
        double pageRelevance = 0;
        for (Lemma lemma : requestLemmas) {
            Optional<List<Index>> indexListOpt = indexRepository.getIndexByPageIdAndLemmaId(page.getId(), lemma.getId());
            if (indexListOpt.isPresent() && indexListOpt.get().size() == 1) {
                pageRelevance += indexListOpt.get().iterator().next().getRank();
            }
        }
        return pageRelevance;
    }

    private List<SearchData> fillResponseDataList(Map<Page, Double> pagesRelevance,
                                                  List<Lemma> requestLemmas, SearchRequestParams params) {
        log.debug("fillResponseDataList enter");
        List<SearchData> data = new ArrayList<>();
        int index = 0;
        int startIndex = params.getOffset();
        int endIndex = startIndex + params.getLimit();
        for (Page page : pagesRelevance.keySet()) {
            if (index >= startIndex && index < endIndex) {
                SearchData searchData = new SearchData();
                searchData.setSite(page.getSite().getUrl());
                searchData.setSiteName(page.getSite().getName());
                searchData.setUri(page.getPath());
                searchData.setTitle(getPageTitle(page, requestLemmas));
                searchData.setSnippet(getPageSnippet(page, requestLemmas));
                searchData.setRelevance(pagesRelevance.get(page));
                data.add(searchData);
                System.out.println(index + " - " + page.getPath());
            }
            index++;
        }
        return data;
    }

    private String getPageTitle(Page page, List<Lemma> requestLemmas) {
        log.debug("getPageTitle enter");
        Pattern pattern = Pattern.compile("<title>.+</title>");
        Matcher matcher = pattern.matcher(page.getContent());
        if (matcher.find()) {
            String title = page.getContent().substring(matcher.start(), matcher.end());
            title = lemmasFinder.deleteHtmlTags(title);
            return boldLemmasInText(title, requestLemmas);
        }
        return "";
    }

    private String getPageSnippet(Page page, List<Lemma> requestLemmas) {
        log.debug("getPageSnippet enter");
        StringBuilder snippet = new StringBuilder();
        String textWithoutTags = lemmasFinder.deleteHtmlTags(page.getContent());
        textWithoutTags = textWithoutTags.replaceAll("\\s+", " ");
        for (Lemma requestLemma : requestLemmas) {
            Optional<List<Index>> indexListOpt = indexRepository.getIndexByPageIdAndLemmaId(page.getId(), requestLemma.getId());
            if (!indexListOpt.isPresent() || indexListOpt.get().size() == 0) continue;
            String lemma = requestLemma.getLemma();
            if (requestLemmas.size() == 1) {
                addTextWithLemmasToSnippet(textWithoutTags, lemma, snippet, true);
            } else if (isLemmaAlreadyInSnippet(snippet.toString(), lemma)) {
                snippet = new StringBuilder();
                addTextWithLemmasToSnippet(textWithoutTags, lemma, snippet, true);
            } else {
                addTextWithLemmasToSnippet(textWithoutTags, lemma, snippet, false);
            }
        }
        return boldLemmasInText(snippet.toString(), requestLemmas);
    }

    private void addTextWithLemmasToSnippet(String textWithoutTags, String lemma, StringBuilder snippet, boolean isOneLemmaInRequest) {
        Matcher matcher = getContainsLemmaMatcher(textWithoutTags, lemma);
        int lemmaEndIndex = 0;
        while (matcher.find(lemmaEndIndex) && snippet.length() + 2 * OFFSET + lemma.length() < SNIPPET_LENGTH) {
            int lemmaStartIndex = matcher.start(1);
            lemmaEndIndex = matcher.end(1);
            String word = textWithoutTags.substring(lemmaStartIndex, lemmaEndIndex);
            if (lemmasFinder.isWordRelatedToBaseForm(word, lemma)) {
                snippet.append(cutPartOfWords(constructSnippet(lemmaStartIndex, textWithoutTags))).append("... ");
                if (!isOneLemmaInRequest) return;
            }
        }
    }

    private boolean isLemmaAlreadyInSnippet(String snippet, String lemma) {
        log.debug("isSnippetNotConstructed enter");
        Matcher matcher = getContainsLemmaMatcher(snippet, lemma);
        return matcher.find();
    }

    private String constructSnippet(int lemmaIndex, String pageTextWithoutTags) {
        log.debug("constructSnippet enter");
        String result;
        if (lemmaIndex - OFFSET > 0) {
            if (lemmaIndex + OFFSET < pageTextWithoutTags.length()) {
                result = pageTextWithoutTags.substring(lemmaIndex - OFFSET, lemmaIndex + OFFSET);
            } else {
                result = pageTextWithoutTags.substring(lemmaIndex - OFFSET);
            }
        } else {
            if (lemmaIndex + OFFSET < pageTextWithoutTags.length()) {
                result = pageTextWithoutTags.substring(0, lemmaIndex + OFFSET);
            } else {
                result = pageTextWithoutTags;
            }
        }
        return result;
    }

    private String cutPartOfWords(String word) {
        log.debug("cutPartOfWords enter");
        word = word.length() > SNIPPET_LENGTH ? word.substring(0, SNIPPET_LENGTH) : word;
        int firstSpace = word.indexOf(" ");
        int lastSpace = word.lastIndexOf(" ");
        if (firstSpace + 1 < lastSpace) {
            return word.substring(firstSpace + 1, lastSpace);
        } else {
            return word;
        }
    }

    private Matcher getContainsLemmaMatcher(String text, String lemma) {
        log.debug("getContainsLemmaMatcher enter");
        int wordEnding = lemma.length() > 5 ? 3 : 1;
        String lemmaWithoutEnding = lemma.substring(0, lemma.length() - wordEnding);
        Pattern pattern = Pattern.compile("[\\s\\p{P}](" + lemmaWithoutEnding + "[а-яА-ЯёЁa-zA-Z]{1," + wordEnding + 1 + "}" + ")[\\s\\p{P}]",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        return pattern.matcher(text);
    }

    private String boldLemmasInText(String text, List<Lemma> requestLemmas) {
        log.debug("boldLemmasInText enter");
        List<String> uniqueLemmas = requestLemmas.stream()
                .map(Lemma::getLemma).distinct().collect(Collectors.toList());
        for (String lemma : uniqueLemmas)
            text = makeFoundLemmasBoldInText(text, lemma);
        return text;
    }

    private String makeFoundLemmasBoldInText(String text, String lemma) {
        log.debug("makeFoundLemmasBoldInText enter");
        Matcher matcher;
        int lemmaEndIndex = 0;
        do {
            matcher = getContainsLemmaMatcher(text, lemma);
            if (matcher.find(lemmaEndIndex)) {
                String word = matcher.group(1);
                int lemmaStartIndex = matcher.start(1);
                lemmaEndIndex = matcher.end(1);
                if (lemmasFinder.isWordRelatedToBaseForm(word, lemma)) {
                    text = text.substring(0, lemmaStartIndex) + "<b>" + word + "</b>" + text.substring(lemmaEndIndex);
                    lemmaEndIndex += 6;
                }
            }
        } while (lemmaEndIndex < text.length() - 6 && matcher.find(lemmaEndIndex));
        return text;
    }

}
