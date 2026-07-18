package dev.minibank.ledger

import spock.lang.Specification
import spock.lang.Timeout

import java.util.concurrent.TimeUnit

/**
 * A Spock specification for the FX client's resilience · the circuit-breaker
 * behaviour stated as a spec: when the FX service is unreachable, the client
 * must not hang and must not throw, it returns a usable fallback rate quickly.
 */
class FxClientSpec extends Specification {

    @Timeout(value = 3, unit = TimeUnit.SECONDS)
    def "when the FX service is down, the client returns a positive fallback rate without throwing"() {
        when: "we call an address where nothing is listening"
        def rate = FxClient.from("http://localhost:19")   // reserved, nothing there

        then: "we get a labelled, sane rate back rather than an exception or a hang"
        rate != null
        rate.source().startsWith("fx down")
        rate.rate() > 0.0
    }
}
