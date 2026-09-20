package fr.nacre.media
import com.google.protobuf.DescriptorProtos
import com.google.protobuf.Timestamp
import com.google.type.LatLng
import com.google.firestore.v1.Value
import org.junit.Assert.assertEquals
import org.junit.Test
class FirebaseProtobufCompatibilityTest {
    @Test fun firestoreAndNewPipeRuntimeTypesCoexist() {
        val point = LatLng.newBuilder().setLatitude(48.8).setLongitude(2.3).build()
        val value = Value.newBuilder().setGeoPointValue(point).build()
        assertEquals(point, Value.parseFrom(value.toByteArray()).geoPointValue)
        val time = Value.newBuilder().setTimestampValue(Timestamp.newBuilder().setSeconds(42)).build()
        assertEquals(42L, Value.parseFrom(time.toByteArray()).timestampValue.seconds)
        val descriptor = DescriptorProtos.DescriptorProto.newBuilder().setName("Echo").build()
        assertEquals("Echo", DescriptorProtos.DescriptorProto.parseFrom(descriptor.toByteArray()).name)
    }
}
