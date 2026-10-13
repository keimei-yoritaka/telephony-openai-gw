package com.example.telephonygw.sip;

public interface RegistrationAddressObserver {
    void onRegistrationReflexiveAddressDetected(String slotId, String publicAddress, int publicPort);
}
