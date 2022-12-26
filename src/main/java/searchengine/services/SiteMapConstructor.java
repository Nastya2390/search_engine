package searchengine.services;

import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.RecursiveAction;

public class SiteMapConstructor extends RecursiveAction {
    private Node node;
    private PageRepository pageRepository;
    private SiteRepository siteRepository;
    private IndexingService indexingService;
    volatile static Boolean isInterrupted = false;

    public SiteMapConstructor(Node node, PageRepository pageRepository,
                              SiteRepository siteRepository,
                              IndexingService indexingService) {
        this.node = node;
        this.pageRepository = pageRepository;
        this.siteRepository = siteRepository;
        this.indexingService = indexingService;
    }

    @Override
    protected void compute() {
        try {
            if (!isInterrupted) {
                if (pageRepository.getPageByPath(node.getPage().getPath()).isPresent()) return;
                pageRepository.save(node.getPage());
                if(isPageCodeValid(Objects.requireNonNull(node.getPage()).getCode()))
                    indexingService.indexPage(node.getSiteUrl() + node.getPage().getPath());
                node.getPage().getSite().setStatusTime(LocalDateTime.now());
                siteRepository.save(node.getPage().getSite());
                List<SiteMapConstructor> taskList = new ArrayList<>();
                for (Node child : node.getChildren()) {
                    SiteMapConstructor task = new SiteMapConstructor(child, pageRepository, siteRepository, indexingService);
                    task.fork();
                    taskList.add(task);
                }
                for (SiteMapConstructor task : taskList) {
                    task.join();
                }
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    public boolean isPageCodeValid(int code) {
        return !String.valueOf(code).startsWith("[4,5]");
    }

}

