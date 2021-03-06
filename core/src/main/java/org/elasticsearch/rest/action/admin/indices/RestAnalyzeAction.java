/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.rest.action.admin.indices;

import org.elasticsearch.action.admin.indices.analyze.AnalyzeRequest;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.ParseFieldMatcher;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.action.RestActions;
import org.elasticsearch.rest.action.RestToXContentListener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.elasticsearch.rest.RestRequest.Method.GET;
import static org.elasticsearch.rest.RestRequest.Method.POST;

public class RestAnalyzeAction extends BaseRestHandler {

    public static class Fields {
        public static final ParseField ANALYZER = new ParseField("analyzer");
        public static final ParseField TEXT = new ParseField("text");
        public static final ParseField FIELD = new ParseField("field");
        public static final ParseField TOKENIZER = new ParseField("tokenizer");
        public static final ParseField TOKEN_FILTERS = new ParseField("filter");
        public static final ParseField CHAR_FILTERS = new ParseField("char_filter");
        public static final ParseField EXPLAIN = new ParseField("explain");
        public static final ParseField ATTRIBUTES = new ParseField("attributes");
    }

    @Inject
    public RestAnalyzeAction(Settings settings, RestController controller) {
        super(settings);
        controller.registerHandler(GET, "/_analyze", this);
        controller.registerHandler(GET, "/{index}/_analyze", this);
        controller.registerHandler(POST, "/_analyze", this);
        controller.registerHandler(POST, "/{index}/_analyze", this);
    }

    @Override
    public RestChannelConsumer prepareRequest(final RestRequest request, final NodeClient client) throws IOException {

        String[] texts = request.paramAsStringArrayOrEmptyIfAll("text");

        AnalyzeRequest analyzeRequest = new AnalyzeRequest(request.param("index"));
        analyzeRequest.text(texts);
        analyzeRequest.analyzer(request.param("analyzer"));
        analyzeRequest.field(request.param("field"));
        final String tokenizer = request.param("tokenizer");
        if (tokenizer != null) {
            analyzeRequest.tokenizer(tokenizer);
        }
        for (String filter : request.paramAsStringArray("filter", Strings.EMPTY_ARRAY)) {
            analyzeRequest.addTokenFilter(filter);
        }
        for (String charFilter : request.paramAsStringArray("char_filter", Strings.EMPTY_ARRAY)) {
            analyzeRequest.addTokenFilter(charFilter);
        }
        analyzeRequest.explain(request.paramAsBoolean("explain", false));
        analyzeRequest.attributes(request.paramAsStringArray("attributes", analyzeRequest.attributes()));

        if (RestActions.hasBodyContent(request)) {
            XContentType type = RestActions.guessBodyContentType(request);
            if (type == null) {
                if (texts == null || texts.length == 0) {
                    texts = new String[]{ RestActions.getRestContent(request).utf8ToString() };
                    analyzeRequest.text(texts);
                }
            } else {
                // NOTE: if rest request with xcontent body has request parameters, the parameters does not override xcontent values
                buildFromContent(RestActions.getRestContent(request), analyzeRequest, parseFieldMatcher);
            }
        }

        return channel -> client.admin().indices().analyze(analyzeRequest, new RestToXContentListener<>(channel));
    }

    public static void buildFromContent(BytesReference content, AnalyzeRequest analyzeRequest, ParseFieldMatcher parseFieldMatcher) {
        try (XContentParser parser = XContentHelper.createParser(content)) {
            if (parser.nextToken() != XContentParser.Token.START_OBJECT) {
                throw new IllegalArgumentException("Malformed content, must start with an object");
            } else {
                XContentParser.Token token;
                String currentFieldName = null;
                while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                    if (token == XContentParser.Token.FIELD_NAME) {
                        currentFieldName = parser.currentName();
                    } else if (parseFieldMatcher.match(currentFieldName, Fields.TEXT) && token == XContentParser.Token.VALUE_STRING) {
                        analyzeRequest.text(parser.text());
                    } else if (parseFieldMatcher.match(currentFieldName, Fields.TEXT) && token == XContentParser.Token.START_ARRAY) {
                        List<String> texts = new ArrayList<>();
                        while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
                            if (token.isValue() == false) {
                                throw new IllegalArgumentException(currentFieldName + " array element should only contain text");
                            }
                            texts.add(parser.text());
                        }
                        analyzeRequest.text(texts.toArray(new String[texts.size()]));
                    } else if (parseFieldMatcher.match(currentFieldName, Fields.ANALYZER) && token == XContentParser.Token.VALUE_STRING) {
                        analyzeRequest.analyzer(parser.text());
                    } else if (parseFieldMatcher.match(currentFieldName, Fields.FIELD) && token == XContentParser.Token.VALUE_STRING) {
                        analyzeRequest.field(parser.text());
                    } else if (parseFieldMatcher.match(currentFieldName, Fields.TOKENIZER)) {
                        if (token == XContentParser.Token.VALUE_STRING) {
                            analyzeRequest.tokenizer(parser.text());
                        } else if (token == XContentParser.Token.START_OBJECT) {
                            analyzeRequest.tokenizer(parser.map());
                        } else {
                            throw new IllegalArgumentException(currentFieldName + " should be tokenizer's name or setting");
                        }
                    } else if (parseFieldMatcher.match(currentFieldName, Fields.TOKEN_FILTERS)
                            && token == XContentParser.Token.START_ARRAY) {
                        while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
                            if (token == XContentParser.Token.VALUE_STRING) {
                                analyzeRequest.addTokenFilter(parser.text());
                            } else if (token == XContentParser.Token.START_OBJECT) {
                                analyzeRequest.addTokenFilter(parser.map());
                            } else {
                                throw new IllegalArgumentException(currentFieldName
                                        + " array element should contain filter's name or setting");
                            }
                        }
                    } else if (parseFieldMatcher.match(currentFieldName, Fields.CHAR_FILTERS)
                            && token == XContentParser.Token.START_ARRAY) {
                        while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
                            if (token == XContentParser.Token.VALUE_STRING) {
                                analyzeRequest.addCharFilter(parser.text());
                            } else if (token == XContentParser.Token.START_OBJECT) {
                                analyzeRequest.addCharFilter(parser.map());
                            } else {
                                throw new IllegalArgumentException(currentFieldName
                                        + " array element should contain char filter's name or setting");
                            }
                        }
                    } else if (parseFieldMatcher.match(currentFieldName, Fields.EXPLAIN)) {
                        if (parser.isBooleanValue()) {
                            analyzeRequest.explain(parser.booleanValue());
                        } else {
                            throw new IllegalArgumentException(currentFieldName + " must be either 'true' or 'false'");
                        }
                    } else if (parseFieldMatcher.match(currentFieldName, Fields.ATTRIBUTES) && token == XContentParser.Token.START_ARRAY) {
                        List<String> attributes = new ArrayList<>();
                        while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
                            if (token.isValue() == false) {
                                throw new IllegalArgumentException(currentFieldName + " array element should only contain attribute name");
                            }
                            attributes.add(parser.text());
                        }
                        analyzeRequest.attributes(attributes.toArray(new String[attributes.size()]));
                    } else {
                        throw new IllegalArgumentException("Unknown parameter ["
                                + currentFieldName + "] in request body or parameter is of the wrong type[" + token + "] ");
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse request body", e);
        }
    }
}
