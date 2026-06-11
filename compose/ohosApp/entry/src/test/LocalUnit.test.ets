import { describe, beforeAll, beforeEach, afterEach, afterAll, it, expect } from '@ohos/hypium';
import { decodeOhosSocketIoFrame } from '../main/ets/kuikly/OhosSocketIoClient';

export default function localUnitTest() {
  describe('localUnitTest',() => {
    // Defines a test suite. Two parameters are supported: test suite name and test suite function.
    beforeAll(() => {
      // Presets an action, which is performed only once before all test cases of the test suite start.
      // This API supports only one parameter: preset action function.
    });
    beforeEach(() => {
      // Presets an action, which is performed before each unit test case starts.
      // The number of execution times is the same as the number of test cases defined by **it**.
      // This API supports only one parameter: preset action function.
    });
    afterEach(() => {
      // Presets a clear action, which is performed after each unit test case ends.
      // The number of execution times is the same as the number of test cases defined by **it**.
      // This API supports only one parameter: clear action function.
    });
    afterAll(() => {
      // Presets a clear action, which is performed after all test cases of the test suite end.
      // This API supports only one parameter: clear action function.
    });
    it('assertContain', 0, () => {
      // Defines a test case. This API supports three parameters: test case name, filter parameter, and test case function.
      let a = 'abc';
      let b = 'b';
      // Defines a variety of assertion methods, which are used to declare expected boolean conditions.
      expect(a).assertContain(b);
      expect(a).assertEqual(a);
    });
    it('decodesSocketIoFrames', 0, () => {
      const open = decodeOhosSocketIoFrame('0{"sid":"sid-1","pingInterval":25000,"pingTimeout":20000}');
      expect(open.kind).assertEqual('engine_open');
      expect(open.pingInterval).assertEqual(25000);
      expect(open.pingTimeout).assertEqual(20000);

      expect(decodeOhosSocketIoFrame('2').kind).assertEqual('engine_ping');
      expect(decodeOhosSocketIoFrame('1').kind).assertEqual('engine_close');
      expect(decodeOhosSocketIoFrame('40{"sid":"socket-1"}').kind).assertEqual('socket_connect');
      expect(decodeOhosSocketIoFrame('41').kind).assertEqual('socket_disconnect');

      const connectError = decodeOhosSocketIoFrame('44{"message":"Unauthorized"}');
      expect(connectError.kind).assertEqual('socket_connect_error');
      expect(connectError.message).assertContain('Unauthorized');

      const event = decodeOhosSocketIoFrame('42["message:new",{"id":"msg-1","content":"hello"}]');
      expect(event.kind).assertEqual('socket_event');
      expect(event.eventName).assertEqual('message:new');
      expect((event.payload as Record<string, string>)['id']).assertEqual('msg-1');
    });
  });
}
