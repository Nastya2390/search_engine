package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import searchengine.dto.indexing.Response;

@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService {

    @Override
    public Response search() {
        return null;
    }

}
