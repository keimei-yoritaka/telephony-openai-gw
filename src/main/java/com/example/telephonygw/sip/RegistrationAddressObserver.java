package com.example.telephonygw.sip;

public interface RegistrationAddressObserver {
    void onRegistrationReflexiveAddressDetected(String publicAddress, int publicPort);
}
