package com.onedata.portal.service.assistant.nl2lf;

public interface LfGroundingService {
    LogicalForm ground(LogicalForm draft, MetadataContext metadataContext);
}
