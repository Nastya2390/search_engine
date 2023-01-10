package searchengine.services;

import searchengine.model.Page;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.RecursiveAction;

public class SiteMapConstructor extends RecursiveAction {
    private final List<Node> rootNodes;
    private final PageRepository pageRepository;
    private final SiteRepository siteRepository;
    private final IndexingService indexingService;
    volatile static Boolean isInterrupted = false;

    public SiteMapConstructor(List<Node> rootNodes, PageRepository pageRepository,
                              SiteRepository siteRepository,
                              IndexingService indexingService) {
        this.rootNodes = rootNodes;
        this.pageRepository = pageRepository;
        this.siteRepository = siteRepository;
        this.indexingService = indexingService;
    }

    @Override
    protected void compute() {
        if (!isInterrupted) {
            if (rootNodes.size() == 1) {
                performPageIndexing(rootNodes.get(0));
            } else {
                int middle = rootNodes.size() / 2;
                SiteMapConstructor left = new SiteMapConstructor(rootNodes.subList(0, middle),
                        pageRepository, siteRepository, indexingService);
                SiteMapConstructor right = new SiteMapConstructor(rootNodes.subList(middle, rootNodes.size()),
                        pageRepository, siteRepository, indexingService);
                left.fork();
                right.fork();
                left.join();
                right.join();
            }
        }
    }

    private void performPageIndexing(Node node) {
        Page page = node.getPage();
        if (pageRepository.getPageByPathAndSite(page.getPath(), page.getSite()).isPresent())
            return;
        pageRepository.save(page);
        indexingService.savePageLemmasToDB(page);
        page.getSite().setStatusTime(LocalDateTime.now());
        siteRepository.save(page.getSite());
        runTasksForChildrenPages(node);
    }

    private void runTasksForChildrenPages(Node node) {
        try {
            List<SiteMapConstructor> taskList = new ArrayList<>();
            for (Node child : node.getChildren()) {
                SiteMapConstructor task = new SiteMapConstructor(Collections.singletonList(child),
                        pageRepository, siteRepository, indexingService);
                task.fork();
                taskList.add(task);
            }
            for (SiteMapConstructor task : taskList) {
                task.join();
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

}

