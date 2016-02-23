/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.telephony.gsm;

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.location.Country;
import android.location.CountryDetector;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.provider.Telephony;
import android.telephony.TelephonyManager;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.internal.telephony.ISub;
import com.android.internal.telephony.ImsSMSDispatcher;
import com.android.internal.telephony.SmsStorageMonitor;
import com.android.internal.telephony.SmsUsageMonitor;
import com.android.internal.telephony.TelephonyTest;
import com.android.internal.telephony.TelephonyTestUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.util.HashMap;

public class GsmSmsDispatcherTest extends TelephonyTest {
    @Mock
    private SmsStorageMonitor mSmsStorageMonitor;
    @Mock
    private SmsUsageMonitor mSmsUsageMonitor;
    @Mock
    private android.telephony.SmsMessage mSmsMessage;
    @Mock
    private SmsMessage mGsmSmsMessage;
    @Mock
    private ImsSMSDispatcher mImsSmsDispatcher;
    @Mock
    private GsmInboundSmsHandler mGsmInboundSmsHandler;
    @Mock
    private CountryDetector mCountryDetector;
    @Mock
    private ISub.Stub mISubStub;

    private TelephonyManager mTelephonyManager;

    private GsmSMSDispatcher mGsmSmsDispatcher;

    private HashMap<String, IBinder> mServiceManagerMockedServices = new HashMap<>();

    private class GsmSmsDispatcherTestHandler extends HandlerThread {

        private GsmSmsDispatcherTestHandler(String name) {
            super(name);
        }

        @Override
        public void onLooperPrepared() {
            mGsmSmsDispatcher = new GsmSMSDispatcher(mPhone, mSmsUsageMonitor,
                    mImsSmsDispatcher, mGsmInboundSmsHandler);
            setReady(true);
        }
    }

    @Before
    public void setUp() throws Exception {

        super.setUp(getClass().getSimpleName());

        // Note that this replaces only cached services in ServiceManager. If a service is not found
        // in the cache, a real instance is used.
        replaceInstance(ServiceManager.class, "sCache", null, mServiceManagerMockedServices);
        mServiceManagerMockedServices.put("isub", mISubStub);

        mTelephonyManager = TelephonyManager.from(mContextFixture.getTestDouble());
        doReturn(true).when(mTelephonyManager).getSmsReceiveCapableForPhone(anyInt(), anyBoolean());
        doReturn(true).when(mTelephonyManager).getSmsSendCapableForPhone(
                anyInt(), anyBoolean());
        doReturn(true).when(mSmsStorageMonitor).isStorageAvailable();

        new GsmSmsDispatcherTestHandler(getClass().getSimpleName()).start();
        waitUntilReady();
    }

    @After
    public void tearDown() throws Exception {
        mGsmSmsDispatcher = null;
        super.tearDown();
    }

    @Test @SmallTest
    public void testSmsStatus() {
        mSimulatedCommands.notifySmsStatus("0123056789ABCDEF");
        TelephonyTestUtils.waitForMs(50);
        verify(mSimulatedCommandsVerifier).acknowledgeLastIncomingGsmSms(true,
                Telephony.Sms.Intents.RESULT_SMS_HANDLED, null);
    }

    @Test @MediumTest
    public void testSendSmsToRegularNumber_doesNotNotifyblockedNumberProvider() throws Exception {
        setupMockPermissionChecks();

        mContextFixture.setSystemService(Context.COUNTRY_DETECTOR, mCountryDetector);
        when(mCountryDetector.detectCountry())
                .thenReturn(new Country("US", Country.COUNTRY_SOURCE_SIM));

        mGsmSmsDispatcher.sendText(
                "6501002000", "121" /*scAddr*/, "test sms", null, null, null, null, false);

        verify(mSimulatedCommandsVerifier).sendSMS(anyString(), anyString(), any(Message.class));
        // Blocked number provider is notified about the emergency contact asynchronously.
        TelephonyTestUtils.waitForMs(50);
        assertEquals(0, mFakeBlockedNumberContentProvider.mNumEmergencyContactNotifications);
    }

    @Test @MediumTest
    public void testSendSmsToEmergencyNumber_notifiesBlockedNumberProvider() throws Exception {
        setupMockPermissionChecks();

        mContextFixture.setSystemService(Context.COUNTRY_DETECTOR, mCountryDetector);
        when(mCountryDetector.detectCountry())
                .thenReturn(new Country("US", Country.COUNTRY_SOURCE_SIM));

        mGsmSmsDispatcher.sendText(
                getEmergencyNumberFromSystemPropertiesOrDefault(), "121" /*scAddr*/, "test sms",
                null, null, null, null, false);

        verify(mSimulatedCommandsVerifier).sendSMS(anyString(), anyString(), any(Message.class));
        // Blocked number provider is notified about the emergency contact asynchronously.
        TelephonyTestUtils.waitForMs(50);
        assertEquals(1, mFakeBlockedNumberContentProvider.mNumEmergencyContactNotifications);
    }

    private void setupMockPermissionChecks() throws Exception {
        PackageManager mockPackageManager = mContextFixture.getTestDouble().getPackageManager();
        when(mockPackageManager.getPackagesForUid(anyInt())).thenReturn(new String[]{"foobar"});
        when(mockPackageManager.getPackageInfo(anyString(), anyInt()))
                .thenReturn(new PackageInfo());
        when(mSmsUsageMonitor.check(anyString(), anyInt())).thenReturn(true);
    }

    private String getEmergencyNumberFromSystemPropertiesOrDefault() {
        String systemEmergencyNumbers = SystemProperties.get("ril.ecclist");
        if (systemEmergencyNumbers == null) {
            return "911";
        } else {
            return systemEmergencyNumbers.split(",")[0];
        }
    }
}
