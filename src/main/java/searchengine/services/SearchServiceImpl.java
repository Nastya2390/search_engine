package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
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

@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService {

    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final SiteRepository siteRepository;
    private final LemmasFinder lemmasFinder;
    private final int OFFSET = 50;
    private final int SNIPPET_LENGTH = 400;
    private final double LEMMA_FREQUENCY_COEFFICIENT = 0.7;

    @Override
    public SearchResponse search(SearchRequestParams params) {
        if (params.getQuery().equals(""))
            return new SearchResponse(true, 0, Collections.emptyList());
        Map<String, LemmaInfo> searchInfo = getLemmaFrequenciesInfo(params);
        if (searchInfo.isEmpty())
            return new SearchResponse(true, 0, Collections.emptyList());
        searchInfo = excludeFrequentlyUsedLemma(searchInfo, params.getSite());
        if (searchInfo.isEmpty())
            return new SearchResponse(true, 0, Collections.emptyList());

        searchInfo = sortLemmasByFrequency(searchInfo);

        List<Page> foundPages = getSearchResultPages(searchInfo);
        if (foundPages.isEmpty())
            return new SearchResponse(true, 0, Collections.emptyList());

        List<Lemma> requestLemmasList = searchInfo.values().stream()
                .map(LemmaInfo::getLemmaList).flatMap(Collection::stream).collect(Collectors.toList());
        Map<Page, Double> pagesRelevance = computePagesRelativeRelevance(foundPages, requestLemmasList);

        List<SearchData> data = fillResponseDataList(pagesRelevance, requestLemmasList, params);
        return new SearchResponse(true, pagesRelevance.size(), data);
    }

    private Map<String, LemmaInfo> getLemmaFrequenciesInfo(SearchRequestParams params) {
        Map<String, LemmaInfo> searchInfo = new HashMap<>();
        Map<String, Integer> requestLemmas = lemmasFinder.getTextRusEngLemmas(params.getQuery());
        for (String lemma : requestLemmas.keySet()) {
            Optional<List<Lemma>> lemmaListOpt = lemmaRepository.getLemmaByLemma(lemma);
            if (!lemmaListOpt.isPresent() || lemmaListOpt.get().isEmpty())
                return Collections.emptyMap();
            List<Lemma> lemmaList = lemmaListOpt.get();
            if (!params.getSite().equals("all")) {
                lemmaList = lemmaList.stream()
                        .filter(x -> x.getSite().getUrl().equals(params.getSite())).collect(Collectors.toList());
                if (lemmaList.isEmpty()) return Collections.emptyMap();
            }
            int lemmaFrequency = lemmaList.stream().map(Lemma::getFrequency).reduce(Integer::sum).get();
            searchInfo.put(lemma, new LemmaInfo(lemmaList, lemmaFrequency));
        }
        return searchInfo;
    }

    private Map<String, LemmaInfo> excludeFrequentlyUsedLemma(Map<String, LemmaInfo> lemmas, String site) {
        List<Site> siteList = siteRepository.findAll();
        long frequencyLimit;
        if (site.equals("all")) {
            frequencyLimit = siteList.stream().map(x -> x.getPages().size()).reduce(Integer::sum).get();
        } else {
            List<Site> filteredSiteList = siteList.stream().filter(x -> x.getUrl().equals(site)).collect(Collectors.toList());
            if (filteredSiteList.isEmpty()) return Collections.emptyMap();
            frequencyLimit = Math.round(filteredSiteList.iterator().next().getPages().size());
        }
        Map<String, LemmaInfo> result = new HashMap<>();
        for (String lemma : lemmas.keySet()) {
            if (lemmas.get(lemma).getCommonFrequency() < frequencyLimit * LEMMA_FREQUENCY_COEFFICIENT)
                result.put(lemma, lemmas.get(lemma));
        }
        return result;
    }

    private Map<String, LemmaInfo> sortLemmasByFrequency(Map<String, LemmaInfo> searchInfo) {
        return searchInfo.entrySet().stream()
                .sorted(Map.Entry.comparingByValue()).collect(Collectors.toMap(Map.Entry::getKey,
                        Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));
    }

    private List<Page> getSearchResultPages(Map<String, LemmaInfo> searchInfo) {
        Map<Page, Boolean> searchResult = new HashMap<>();
        boolean hasInitialFill = false;
        for (LemmaInfo lemmaInfo : searchInfo.values()) {
            List<Lemma> lemmaList = lemmaInfo.getLemmaList();
            if (hasInitialFill) {
                markIncorrectPagesInSearchResult(lemmaList, searchResult);
            } else {
                initialSearchResultFill(lemmaList, searchResult);
                hasInitialFill = true;
            }
        }
        return excludeIncorrectPagesFromResult(searchResult);
    }

    private void initialSearchResultFill(List<Lemma> lemmaList, Map<Page, Boolean> searchResult) {
        for (Lemma lemma : lemmaList) {
            Optional<List<Index>> indexListOpt = indexRepository.getIndexByLemma(lemma);
            List<Page> pageList = indexListOpt.get().stream().map(Index::getPage).collect(Collectors.toList());
            for (Page page : pageList) {
                searchResult.put(page, true);
            }
        }
    }

    private void markIncorrectPagesInSearchResult(List<Lemma> lemmaList, Map<Page, Boolean> searchResult) {
        for (Lemma lemma : lemmaList) {
            for (Page page : searchResult.keySet()) {
                Optional<List<Index>> indexListOpt = indexRepository.getIndexByPageIdAndLemmaId(page.getId(), lemma.getId());
                if (!indexListOpt.isPresent() || indexListOpt.get().size() == 0) {
                    searchResult.put(page, false);
                }
            }
        }
    }

    private List<Page> excludeIncorrectPagesFromResult(Map<Page, Boolean> searchResult) {
        List<Page> foundPages = new ArrayList<>();
        for (Page page : searchResult.keySet()) {
            if (searchResult.get(page)) {
                foundPages.add(page);
            }
        }
        return foundPages;
    }

    private Map<Page, Double> computePagesRelativeRelevance(List<Page> foundPages, List<Lemma> requestLemmas) {
        Map<Page, Double> pagesRelevance = new LinkedHashMap<>();
        for (Page page : foundPages) {
            double pageRank = 0;
            for (Lemma lemma : requestLemmas) {
                Optional<List<Index>> indexListOpt = indexRepository.getIndexByPageIdAndLemmaId(page.getId(), lemma.getId());
                if (indexListOpt.isPresent() && indexListOpt.get().size() == 1) {
                    pageRank += indexListOpt.get().iterator().next().getRank();
                }
            }
            pagesRelevance.put(page, pageRank);
        }
        double maxPageRank = pagesRelevance.values().stream().max(Double::compare).get();
        pagesRelevance.replaceAll((p, v) -> pagesRelevance.get(p) / maxPageRank);
        return pagesRelevance.entrySet().stream().sorted(Collections.reverseOrder(Map.Entry.comparingByValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));
    }

    private List<SearchData> fillResponseDataList(Map<Page, Double> pagesRelevance,
                                                  List<Lemma> requestLemmas, SearchRequestParams params) {
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
                searchData.setTitle(boldLemmasInText(page.getTitle(), requestLemmas));
                searchData.setSnippet(getPageSnippet(page, requestLemmas));
                searchData.setRelevance(pagesRelevance.get(page));
                data.add(searchData);
                System.out.println(index + " - " + page.getPath());
            }
            index++;
        }
        return data;
    }

    private String getPageSnippet(Page page, List<Lemma> requestLemmas) {
        StringBuilder snippet = new StringBuilder();
        for (Lemma lemma : requestLemmas) {
            Optional<List<Index>> indexListOpt = indexRepository.getIndexByPageIdAndLemmaId(page.getId(), lemma.getId());
            if (indexListOpt.isPresent() && indexListOpt.get().size() == 1) {
                String rusEngTextWithoutTags = lemmasFinder.getRusEngText(page.getContent());
                rusEngTextWithoutTags = rusEngTextWithoutTags.replaceAll("\\s+", " ");
                int lemmaIndex = rusEngTextWithoutTags.indexOf(lemma.getLemma());
                if (lemmaIndex == -1)
                    lemmaIndex = rusEngTextWithoutTags.indexOf(lemma.getLemma().substring(0, lemma.getLemma().length() - 1));
                snippet.append(constructSnippet(lemmaIndex, rusEngTextWithoutTags)).append("... ");
            }
        }
        return boldLemmasInText(snippet.toString(), requestLemmas);
    }

    private String constructSnippet(int lemmaIndex, String pageTextWithoutTags) {
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
        return cutPartOfWords(result);
    }

    private String cutPartOfWords(String word) {
        word = word.length() > SNIPPET_LENGTH ? word.substring(0, SNIPPET_LENGTH) : word;
        int firstSpace = word.indexOf(" ");
        int lastSpace = word.lastIndexOf(" ");
        if (firstSpace + 1 < lastSpace) {
            return word.substring(firstSpace + 1, lastSpace);
        } else {
            return word;
        }
    }

    private String boldLemmasInText(String text, List<Lemma> requestLemmas) {
        List<String> uniqueLemmas = requestLemmas.stream()
                .map(Lemma::getLemma).distinct().collect(Collectors.toList());
        for (String lemma : uniqueLemmas) {
            Pattern pattern = Pattern.compile(lemma.substring(0, lemma.length() - 1) + "[А-Яа-яa-zA-Z]+[\\s\\p{P}<]{1}",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            Matcher matcher = pattern.matcher(text);

            List<String> matches = new ArrayList<>();
            while (matcher.find()) {
                matches.add(text.substring(matcher.start(), matcher.end()));
            }
            matches = matches.stream().distinct().sorted((o1, o2) -> o2.length() - o1.length()).collect(Collectors.toList());

            for (String match : matches) {
                text = text.replaceAll(match, "<b>" + match + "</b>");
            }
        }
        return text;
    }

}
