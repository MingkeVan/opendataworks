package com.onedata.portal.dto.dolphin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import java.util.List;

/**
 * Paginated list response from DolphinScheduler.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DolphinPageData<T> {

    private List<T> totalList;
    private int total;
    private int totalPage;
    private int pageSize;
    private int currentPage;
    private int start;
}
