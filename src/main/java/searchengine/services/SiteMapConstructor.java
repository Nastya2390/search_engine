package searchengine.services;

import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RecursiveAction;

public class SiteMapConstructor extends RecursiveAction {
    private Node node;
    private PageRepository pageRepository;
    private SiteRepository siteRepository;
    volatile static Boolean isInterrupted = false;

    public SiteMapConstructor(Node node, PageRepository pageRepository,
                              SiteRepository siteRepository) {
        this.node = node;
        this.pageRepository = pageRepository;
        this.siteRepository = siteRepository;
    }

    @Override
    protected void compute() {
        try {
            if (!isInterrupted) {
                if (pageRepository.getPageByPath(node.getPage().getPath()).isPresent()) return;
                pageRepository.save(node.getPage());
                node.getPage().getSite().setStatusTime(LocalDateTime.now());
                siteRepository.save(node.getPage().getSite());
                List<SiteMapConstructor> taskList = new ArrayList<>();
                for (Node child : node.getChildren()) {
                    SiteMapConstructor task = new SiteMapConstructor(child, pageRepository, siteRepository);
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
}

