package searchengine.dto;

import lombok.Data;

@Data
public class ResponseError {
    private boolean result;
    private String error;

    public ResponseError(boolean result, String error) {
        this.result = result;
        this.error = error;
    }
}
