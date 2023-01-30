package searchengine.utils;

import lombok.extern.slf4j.Slf4j;
import searchengine.model.IndexingStatus;
import searchengine.model.Page;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;
import searchengine.services.IndexingService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.RecursiveAction;

@Slf4j
public class SiteMapConstructor extends RecursiveAction {
    private final List<Node> rootNodes;
    private final PageRepository pageRepository;
    private final SiteRepository siteRepository;
    private final IndexingService indexingService;
    public volatile static Boolean isInterrupted = false;
    public volatile static Boolean indexingRunning = false;

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

    public synchronized void performPageIndexing(Node node) {
        Page page = node.getPage();
        if (pageRepository.getPageByPathAndSite(page.getPath(), page.getSite()).isPresent())
            return;
        pageRepository.save(page);
        indexingService.savePageLemmasToDB(page);
        page.getSite().setStatusTime(LocalDateTime.now());
        siteRepository.save(page.getSite());
        runTasksForChildrenPages(node);
        if (isRootPage(page)) {
            page.getSite().setStatus(IndexingStatus.INDEXED);
            siteRepository.save(page.getSite());
        }
    }

    private boolean isRootPage(Page page) {
        return page.getPath().equals("/");
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
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

}

