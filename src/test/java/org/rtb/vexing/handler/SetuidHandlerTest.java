package org.rtb.vexing.handler;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.Json;
import io.vertx.ext.web.Cookie;
import io.vertx.ext.web.RoutingContext;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.rtb.vexing.VertxTest;
import org.rtb.vexing.cookie.UidsCookie;
import org.rtb.vexing.cookie.UidsCookieFactory;
import org.rtb.vexing.metric.CookieSyncMetrics;
import org.rtb.vexing.metric.CookieSyncMetrics.BidderCookieSyncMetrics;
import org.rtb.vexing.metric.MetricName;
import org.rtb.vexing.metric.Metrics;
import org.rtb.vexing.model.UidWithExpiry;
import org.rtb.vexing.model.Uids;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

public class SetuidHandlerTest extends VertxTest {

    private static final String RUBICON = "rubicon";
    private static final String ADNXS = "adnxs";

    @Rule
    public final MockitoRule mockitoRule = MockitoJUnit.rule();

    @Mock
    private UidsCookieFactory uidsCookieFactory;
    @Mock
    private Metrics metrics;
    @Mock
    private CookieSyncMetrics cookieSyncMetrics;
    @Mock
    private BidderCookieSyncMetrics bidderCookieSyncMetrics;

    private SetuidHandler setuidHandler;

    @Mock
    private RoutingContext routingContext;
    @Mock
    private HttpServerRequest httpRequest;
    @Mock
    private HttpServerResponse httpResponse;

    @Before
    public void setUp() {
        given(routingContext.request()).willReturn(httpRequest);
        given(routingContext.response()).willReturn(httpResponse);

        given(routingContext.addCookie(any())).willReturn(routingContext);

        given(metrics.cookieSync()).willReturn(cookieSyncMetrics);
        given(cookieSyncMetrics.forBidder(anyString())).willReturn(bidderCookieSyncMetrics);

        setuidHandler = new SetuidHandler(uidsCookieFactory, metrics);
    }

    @Test
    public void creationShouldFailOnNullArguments() {
        assertThatNullPointerException().isThrownBy(() -> new SetuidHandler(null, null));
        assertThatNullPointerException().isThrownBy(() -> new SetuidHandler(uidsCookieFactory, null));
    }

    @Test
    public void shouldRespondWithErrorIfOptedOut() {
        // given
        given(uidsCookieFactory.parseFromRequest(any()))
                .willReturn(new UidsCookie(Uids.builder().optout(true).build()));

        given(httpResponse.setStatusCode(anyInt())).willReturn(httpResponse);

        // when
        setuidHandler.setuid(routingContext);

        // then
        verify(httpResponse).setStatusCode(eq(401));
        verify(httpResponse).end();
        verifyNoMoreInteractions(httpResponse);
    }

    @Test
    public void shouldRespondWithErrorIfBidderParamIsMissing() {
        // given
        given(uidsCookieFactory.parseFromRequest(any())).willReturn(new UidsCookie(Uids.builder().build()));

        given(httpResponse.setStatusCode(anyInt())).willReturn(httpResponse);

        // when
        setuidHandler.setuid(routingContext);

        // then
        verify(httpResponse).setStatusCode(eq(400));
        verify(httpResponse).end();
        verifyNoMoreInteractions(httpResponse);
    }

    @Test
    public void shouldRemoveUidFromCookieIfMissingInRequest() {
        // given
        final Map<String, UidWithExpiry> uids = new HashMap<>();
        uids.put(RUBICON, UidWithExpiry.live("J5VLCWQP-26-CWFT"));
        uids.put(ADNXS, UidWithExpiry.live("12345"));
        given(uidsCookieFactory.parseFromRequest(any())).willReturn(new UidsCookie(Uids.builder().uids(uids).build()));

        given(httpRequest.getParam("bidder")).willReturn(RUBICON);

        // when
        setuidHandler.setuid(routingContext);

        // then
        final Cookie uidsCookie = captureCookie();
        verify(httpResponse).end();
        // this uids cookie value stands for {"uids":{"adnxs":"12345"}}
        final Uids decodedUids = decodeUids(uidsCookie.getValue());
        assertThat(decodedUids.uids).hasSize(1);
        assertThat(decodedUids.uids.get(ADNXS).uid).isEqualTo("12345");
    }

    @Test
    public void shouldIgnoreFacebookSentinel() {
        // given
        // this uids cookie value stands for {"uids":{"audienceNetwork":"facebookUid"}}
        given(routingContext.getCookie(eq("uids"))).willReturn(Cookie.cookie("uids",
                "eyJ1aWRzIjp7ImF1ZGllbmNlTmV0d29yayI6ImZhY2Vib29rVWlkIn19"));
        final Map<String, UidWithExpiry> uids = new HashMap<>();
        uids.put("audienceNetwork", UidWithExpiry.live("facebookUid"));
        given(uidsCookieFactory.parseFromRequest(any())).willReturn(new UidsCookie(Uids.builder().uids(uids).build()));

        given(httpRequest.getParam("bidder")).willReturn("audienceNetwork");
        given(httpRequest.getParam("uid")).willReturn("0");

        // when
        setuidHandler.setuid(routingContext);

        // then
        final Cookie uidsCookie = captureCookie();
        verify(httpResponse).end();
        // this uids cookie value stands for {"uids":{"audienceNetwork":"facebookUid"}}
        final Uids decodedUids = decodeUids(uidsCookie.getValue());
        assertThat(decodedUids.uids).hasSize(1);
        assertThat(decodedUids.uids.get("audienceNetwork").uid).isEqualTo("facebookUid");
    }

    @Test
    public void shouldUpdateUidInCookieWithRequestValue() {
        // given
        final Map<String, UidWithExpiry> uids = new HashMap<>();
        uids.put(RUBICON, UidWithExpiry.live("J5VLCWQP-26-CWFT"));
        uids.put(ADNXS, UidWithExpiry.live("12345"));
        given(uidsCookieFactory.parseFromRequest(any())).willReturn(new UidsCookie(Uids.builder().uids(uids).build()));

        given(httpRequest.getParam("bidder")).willReturn(RUBICON);
        given(httpRequest.getParam("uid")).willReturn("updatedUid");

        // when
        setuidHandler.setuid(routingContext);

        // then
        final Cookie uidsCookie = captureCookie();
        verify(httpResponse).end();
        verify(cookieSyncMetrics).forBidder(eq(RUBICON));
        verify(bidderCookieSyncMetrics).incCounter(eq(MetricName.sets));
        // this uids cookie value stands for {"uids":{"adnxs":"12345","rubicon":"updatedUid"}}
        final Uids decodedUids = decodeUids(uidsCookie.getValue());
        assertThat(decodedUids.uids).hasSize(2);
        assertThat(decodedUids.uids.get(RUBICON).uid).isEqualTo("updatedUid");
        assertThat(decodedUids.uids.get(ADNXS).uid).isEqualTo("12345");
    }

    @Test
    public void shouldUpdateOptOutsMetricIfOptedOut() {
        // given
        // this uids cookie value stands for {"optout": true}
        given(uidsCookieFactory.parseFromRequest(any()))
                .willReturn(new UidsCookie(Uids.builder().optout(true).build()));

        given(httpResponse.setStatusCode(anyInt())).willReturn(httpResponse);

        // when
        setuidHandler.setuid(routingContext);

        // then
        verify(cookieSyncMetrics).incCounter(eq(MetricName.opt_outs));
    }

    @Test
    public void shouldUpdateBadRequestsMetricIfBidderParamIsMissing() {
        // given
        given(uidsCookieFactory.parseFromRequest(any())).willReturn(new UidsCookie(Uids.builder().build()));

        given(httpResponse.setStatusCode(anyInt())).willReturn(httpResponse);

        // when
        setuidHandler.setuid(routingContext);

        // then
        verify(cookieSyncMetrics).incCounter(eq(MetricName.bad_requests));
    }

    @Test
    public void shouldUpdateSetsMetric() {
        // given
        given(uidsCookieFactory.parseFromRequest(any())).willReturn(new UidsCookie(Uids.builder().build()));

        given(httpRequest.getParam("bidder")).willReturn(RUBICON);
        given(httpRequest.getParam("uid")).willReturn("updatedUid");

        // when
        setuidHandler.setuid(routingContext);

        // then
        verify(bidderCookieSyncMetrics).incCounter(eq(MetricName.sets));
    }

    private Cookie captureCookie() {
        final ArgumentCaptor<Cookie> cookieCaptor = ArgumentCaptor.forClass(Cookie.class);
        verify(routingContext).addCookie(cookieCaptor.capture());
        return cookieCaptor.getValue();
    }

    private static Uids decodeUids(String value) {
        return Json.decodeValue(Buffer.buffer(Base64.getUrlDecoder().decode(value)), Uids.class);
    }
}
