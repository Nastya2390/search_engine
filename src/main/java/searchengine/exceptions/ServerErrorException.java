package searchengine.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class ServerErrorException extends ResponseStatusException {
    public ServerErrorException(HttpStatus status, String reason) {
        super(status, reason);
    }
}
