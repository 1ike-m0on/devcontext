package com.devcontext.ports.review;

public interface ReviewReportStore {

    String writeReport(String rootPath, Long reviewId, String markdown);
}
