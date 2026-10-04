package com.material.xray.core.model

enum class ConnectionProgress {
    PreparingRuntime,
    PreparingCore,
    UpdatingRoutingData,
    ResolvingEntryServer,
    GeneratingConfiguration,
    StartingCore,
    ConfiguringTunnel,
    ConfiguringRouting,
    WaitingForCore,
    StoppingCore,
    CleaningRuntime,
    InspectingSavedRuntime,
    VerifyingRuntime,
    RestoringControlApi,
    UpdatingNetworkRoute,
    UpdatingAppRouting,
}
