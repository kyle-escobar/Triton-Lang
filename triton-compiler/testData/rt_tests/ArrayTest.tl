package test

import java.lang.System
import java.lang.Object

import org.junit.Assert

fun newArrayTest() {
    int[] ia = new int[10]
    Assert.assertNotNull(ia)

    Object[] oa = new Object[10]
    Assert.assertNotNull(oa)
    Assert.assertNull(oa[0])

    Object[][] multiDimensionalArray = new Object[10][10]
    Assert.assertNotNull(multiDimensionalArray)
    Assert.assertNull(oa[0])
}

fun testLoadAndStore() {
    int num = 10
    int[] ia = new int[10]
    ia[0] = num
    Assert.assertEquals(num, ia[0])

    int[][][] deepArray = new int[1][1][1]
    deepArray[0][0][0] = num
    Assert.assertEquals(num, deepArray[0][0][0])

    Object[] objArray = new Object[10]
    objArray[0] = "Hello World"
    Assert.assertEquals("Hello World", objArray[0])
}

fun arrayLengthTest() {
    int[] ia = new int[10]
    Assert.assertEquals(10, ia.length)

    int[][] ia2d = new int[10][100]
    Assert.assertEquals(10, ia2d.length)
    Assert.assertEquals(100, ia2d[0].length)
    Assert.assertEquals(10, (new int[10]).length);
}
