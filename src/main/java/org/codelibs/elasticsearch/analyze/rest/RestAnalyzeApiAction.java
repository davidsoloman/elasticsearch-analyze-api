package org.codelibs.elasticsearch.analyze.rest;

import static org.elasticsearch.rest.RestStatus.OK;

import java.io.IOException;
import java.io.StringReader;
import java.util.Map;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.util.Attribute;
import org.apache.lucene.util.AttributeReflector;
import org.apache.lucene.util.BytesRef;
import org.codelibs.elasticsearch.analyze.exception.AnalyzeApiRequestException;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.indices.analysis.IndicesAnalysisService;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.BytesRestResponse;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.search.lookup.SourceLookup;

public class RestAnalyzeApiAction extends BaseRestHandler {

    private IndicesService indicesService;

    private IndicesAnalysisService indicesAnalysisService;

    @Inject
    public RestAnalyzeApiAction(final Settings settings, final Client client,
            final RestController controller, IndicesService indicesService,
            IndicesAnalysisService indicesAnalysisService) {
        super(settings, controller, client);
        this.indicesService = indicesService;
        this.indicesAnalysisService = indicesAnalysisService;

        controller.registerHandler(RestRequest.Method.GET, "/_analyze_api",
                this);
        controller.registerHandler(RestRequest.Method.GET,
                "/{index}/_analyze_api", this);
        controller.registerHandler(RestRequest.Method.POST, "/_analyze_api",
                this);
        controller.registerHandler(RestRequest.Method.POST,
                "/{index}/_analyze_api", this);
    }

    @Override
    protected void handleRequest(final RestRequest request,
            final RestChannel channel, Client client) {
        BytesReference content = request.content();
        if (content == null) {
            sendErrorResponse(channel, new AnalyzeApiRequestException(
                    "No contents."));
            return;
        }

        final String defaultIndex = request.param("index");
        final String defaultAnalyzer = request.param("analyzer");

        try {
            final Map<String, Object> sourceAsMap = SourceLookup
                    .sourceAsMap(content);

            final XContentBuilder builder = JsonXContent.contentBuilder();
            if (request.hasParam("pretty")) {
                builder.prettyPrint().lfAtEnd();
            }
            builder.startObject();

            for (Map.Entry<String, Object> entry : sourceAsMap.entrySet()) {
                final String name = entry.getKey();
                @SuppressWarnings("unchecked")
                final Map<String, Object> analyzeData = (Map<String, Object>) entry
                        .getValue();

                String indexName = (String) analyzeData.get("index");
                if (indexName == null) {
                    if (defaultIndex != null) {
                        indexName = defaultIndex;
                    } else {
                        throw new AnalyzeApiRequestException(
                                "index is not found in your request: "
                                        + analyzeData);
                    }
                }
                String analyzerName = (String) analyzeData.get("analyzer");
                if (analyzerName == null) {
                    if (defaultAnalyzer != null) {
                        analyzerName = defaultAnalyzer;
                    } else {
                        throw new AnalyzeApiRequestException(
                                "analyzer is not found in your request: "
                                        + analyzeData);
                    }
                }
                final String text = (String) analyzeData.get("text");
                if (text == null) {
                    throw new AnalyzeApiRequestException(
                            "text is not found in your request: " + analyzeData);
                }

                builder.startArray(name);

                IndexService indexService = null;
                if (indexName != null) {
                    indexService = indicesService.indexServiceSafe(indexName);
                }

                Analyzer analyzer = null;
                if (indexService == null) {
                    analyzer = indicesAnalysisService.analyzer(analyzerName);
                } else {
                    analyzer = indexService.analysisService().analyzer(
                            analyzerName);
                }

                try (TokenStream stream = analyzer.tokenStream(null,
                        new StringReader(text))) {
                    stream.reset();

                    CharTermAttribute term = stream
                            .addAttribute(CharTermAttribute.class);
                    PositionIncrementAttribute posIncr = stream
                            .addAttribute(PositionIncrementAttribute.class);

                    int position = 0;
                    while (stream.incrementToken()) {
                        builder.startObject();

                        int increment = posIncr.getPositionIncrement();
                        if (increment > 0) {
                            position = position + increment;
                        }

                        builder.field("term", term.toString());
                        if (request.paramAsBoolean("position", false)) {
                            builder.field("position", position);
                        }

                        stream.reflectWith(new AttributeReflector() {
                            @Override
                            public void reflect(
                                    Class<? extends Attribute> attClass,
                                    String key, Object value) {
                                String keyName = decamelize(key);
                                if (request.paramAsBoolean(keyName, false)) {
                                    if (value instanceof BytesRef) {
                                        final BytesRef p = (BytesRef) value;
                                        value = p.toString();
                                    }
                                    try {
                                        builder.field(keyName, value);
                                    } catch (IOException e) {
                                        logger.warn("Failed to write " + key
                                                + ":" + value, e);
                                    }
                                }
                            }
                        });

                        builder.endObject();
                    }
                    stream.end();
                }
                builder.endArray();
            }
            builder.endObject();
            channel.sendResponse(new BytesRestResponse(OK, builder));
        } catch (Exception e) {
            sendErrorResponse(channel, e);
        }

    }

    private void sendErrorResponse(final RestChannel channel, final Throwable t) {
        try {
            channel.sendResponse(new BytesRestResponse(channel, t));
        } catch (final Exception e) {
            logger.error("Failed to send a failure response.", e);
        }
    }

    static String decamelize(final String s) {
        if (s == null) {
            return null;
        }
        StringBuilder buf = new StringBuilder(20);
        for (int i = 0; i < s.length(); ++i) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c)) {
                if (buf.length() != 0) {
                    buf.append('_');
                }
                buf.append(Character.toLowerCase(c));
            } else if (c == ' ') {
                buf.append('_');
            } else if (Character.isAlphabetic(c)) {
                buf.append(Character.toLowerCase(c));
            }
        }
        return buf.toString();
    }

}
