package com.lampp.nmaxconnect.feature.bike

import com.lampp.nmaxconnect.core.di.NetworkModule
import com.lampp.nmaxconnect.core.maintenance.Garage
import com.lampp.nmaxconnect.core.maintenance.ServiceItem
import com.lampp.nmaxconnect.core.maintenance.ServiceRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BikeUiStateTest {
    @Test
    fun serviceDueByDistance() {
        val garage = Garage(
            odoKm = 12_500.0,
            services = mapOf(
                ServiceItem.ENGINE_OIL to ServiceRecord(10_000.0),
                ServiceItem.GEAR_OIL to ServiceRecord(10_000.0),
                ServiceItem.AIR_FILTER to ServiceRecord(0.0),
            ),
            intervalsKm = mapOf(ServiceItem.ENGINE_OIL to 2_000),
        )
        val rows = bikeUiState(garage).services.associateBy { it.item }

        val oil = rows.getValue(ServiceItem.ENGINE_OIL)
        assertEquals(-500.0, oil.kmLeft!!, 1e-9) // custom 2,000 km interval
        assertTrue(oil.overdue)

        val gear = rows.getValue(ServiceItem.GEAR_OIL)
        assertEquals(6_500.0, gear.kmLeft!!, 1e-9)
        assertFalse(gear.overdue)
        assertEquals(10_000.0, gear.lastChangeKm, 1e-9)

        assertTrue(rows.getValue(ServiceItem.AIR_FILTER).overdue)

        val belt = rows.getValue(ServiceItem.CVT_BELT)
        assertNull(belt.record)
        assertFalse(belt.overdue)
        assertEquals(ServiceItem.CVT_BELT.defaultKm, belt.intervalKm)
    }

    @Test
    fun unknownOdoNeverOverdue() {
        val plug = bikeUiState(Garage(services = mapOf(ServiceItem.SPARK_PLUG to ServiceRecord(1_000.0))))
            .services.first { it.item == ServiceItem.SPARK_PLUG }
        assertNull(plug.kmLeft)
        assertNull(plug.progress)
        assertFalse(plug.overdue)
    }

    @Test
    fun progressAlongTheInterval() {
        fun progressAt(odo: Double) = bikeUiState(
            Garage(odoKm = odo, services = mapOf(ServiceItem.ENGINE_OIL to ServiceRecord(10_000.0))),
        ).services.first { it.item == ServiceItem.ENGINE_OIL }.progress

        assertEquals(0f, progressAt(10_000.0)!!, 1e-6f)
        assertEquals(0.5f, progressAt(11_500.0)!!, 1e-6f) // 3,000 km default interval
        assertEquals(1f, progressAt(14_000.0)!!, 1e-6f) // overdue stays at the end
        assertEquals(0f, progressAt(9_000.0)!!, 1e-6f) // ODO below the record (typo) clamps
    }

    @Test
    fun neverRecordedCountsFromZero() {
        val rows = bikeUiState(Garage(odoKm = 6_000.0)).services.associateBy { it.item }
        val belt = rows.getValue(ServiceItem.CVT_BELT)
        assertEquals(18_000.0, belt.kmLeft!!, 1e-9) // 24,000 km default interval from 0 km
        assertEquals(0.25f, belt.progress!!, 1e-6f)
        assertFalse(belt.overdue)
        assertTrue("3,000 km interval passed", rows.getValue(ServiceItem.ENGINE_OIL).overdue)
    }

    @Test
    fun savesWithTheOldDateFieldStillLoad() {
        val saved = """{"ENGINE_OIL":{"odoKm":3300.0,"epochDay":20744}}"""
        val services = NetworkModule.provideJson().decodeFromString<Map<ServiceItem, ServiceRecord>>(saved)
        assertEquals(mapOf(ServiceItem.ENGINE_OIL to ServiceRecord(3_300.0)), services)
    }
}
