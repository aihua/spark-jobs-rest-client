package com.gamegenetics.sparkrestclient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.protocol.HTTP;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Created by yonatan on 08.10.15.
 */
@Setter(AccessLevel.PACKAGE)
@Getter(AccessLevel.PUBLIC)
public final class SparkRestClient {

    SparkRestClient() {}

    private SparkVersion sparkVersion;

    private Boolean supervise;

    private String masterUrl;

    private Map<String,String> environmentVariables;

    private Boolean eventLogDisabled;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CONTENT_TYPE_HEADER = "content-type";
    private static final String MIME_TYPE_JSON = "application/json";
    private static final String DEPLOY_MODE_CLUSTER = "cluster";
    private static final String CHARSET_UTF_8 = "charset=UTF-8";
    private static final String MIME_TYPE_JSON_UTF_8 = MIME_TYPE_JSON + ";" + CHARSET_UTF_8;

    private final HttpClient client = HttpClientBuilder.create().build();

    /**
     *
     * @param appName name of your Spark job
     * @param mainClass class containing the main() method which defines the Spark application driver and tasks
     * @param appResource location of jar which contains application containing your <code>mainClass</code>
     * @param appArgs args needed by the main() method of your <code>mainClass</code>
     * @param jars other
     * @return SubmissionId of task submitted to the Spark cluster, if submission was successful.
     * Please note that a successful submission does not guarantee successful deployment of app.
     * @throws FailedSparkRequestException iff submission failed
     */
    public String submitJob(final String appName,
                            final String mainClass,
                            final String appResource,
                            final List<String> appArgs,
                            final Set<String> jars) throws FailedSparkRequestException {
        final JobSubmitRequest jobSubmitRequest = JobSubmitRequest.builder()
                .action(Action.CreateSubmissionRequest)
                .appArgs(appArgs)
                .appResource(appResource)
                .clientSparkVersion(sparkVersion.toString())
                .mainClass(mainClass)
                .environmentVariables(environmentVariables)
                .sparkProperties(
                        JobSubmitRequest.SparkProperties.builder()
                                .jars(jars(appResource, jars))
                                .appName(appName)
                                .eventLogEnabled(eventLogDisabled)
                                .driverSupervise(supervise)
                                .master(masterUrl)
                                .build()
                )
                .build();

        final String url = "http://" + masterUrl + "/v1/submissions/create";

        final HttpPost post = new HttpPost(url);
        post.setHeader(HTTP.CONTENT_TYPE, MIME_TYPE_JSON_UTF_8);

        try {
            final String message = MAPPER.writeValueAsString(jobSubmitRequest);
            post.setEntity(new StringEntity(message));
        } catch (UnsupportedEncodingException | JsonProcessingException e) {
            throw new FailedSparkRequestException(e);
        }

        final JobSubmitResponse response = executeHttpMethodAndGetResponse(post, JobSubmitResponse.class);

        return response.getSubmissionId();
    }

    String jars(String appResource, Set<String> jars) {
        final Set<String> output = Stream.of(appResource).collect(Collectors.toSet());
        Optional.ofNullable(jars).ifPresent(j -> output.addAll(j));
        return String.join(",",output);
    }

    public void killJob(final String submissionId) throws FailedSparkRequestException {
        assertSubmissionId(submissionId);
        final String url = "http://" + masterUrl + "/v1/submissions/kill/" + submissionId;
        executeHttpMethodAndGetResponse(new HttpPost(url), SparkKillJobResponse.class);
    }

    public DriverState jobStatus(final String submissionId) throws FailedSparkRequestException {
        assertSubmissionId(submissionId);
        final String url = "http://" + masterUrl + "/v1/submissions/status/" + submissionId;
        final JobStatusResponse response = executeHttpMethodAndGetResponse(new HttpGet(url),JobStatusResponse.class);
        if (!response.getSuccess()) {
            throw new FailedSparkRequestException("Spark master failed executing the status request.");
        }
        return response.getDriverState();
    }

    private<T extends AbstractSparkResponse>  T executeHttpMethodAndGetResponse(HttpRequestBase httpRequest, Class<T> responseClass) throws FailedSparkRequestException {
        T response;
        try {
            final String stringResponse = client.execute(httpRequest,new BasicResponseHandler());
            if (stringResponse!= null) {
                response = (T) MAPPER.readValue(stringResponse,responseClass);
            } else {
                throw new FailedSparkRequestException("Received empty string response");
            }
        } catch (IOException e) {
            throw new FailedSparkRequestException(e);
        }

        if ( response == null || !response.getSuccess()) {
            throw new FailedSparkRequestException("Spark master failed executing the kill.");
        }

        return response;
    }

    private void assertSubmissionId(final String submissionId) {
        if (submissionId == null
                || submissionId.isEmpty()
                || submissionId.trim().equals("")) {
            throw new IllegalArgumentException("SubmissionId must be a non blank string");
        }
    }

}