"""Narrow dispatch/linkage contract: selector strings cross the Kuikly ObjC runtime boundary."""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
bridge = (root / 'iosApp/KuiklyExpand/Modules/HRBridgeModule.m').read_text()
project = (root / 'iosApp.xcodeproj/project.pbxproj').read_text()
assert re.search(r'@implementation\s+HRBridgeModule\b', bridge)
assert re.search(r'-\s*\(void\)adaptiveNavigation:\(NSDictionary\s*\*\)args\s*\{', bridge)
assert 'SlockAdaptiveNavigationSession.m in Sources' in project
assert 'HRBridgeModule.m in Sources' in project
print('PASS: adaptiveNavigation ObjC selector and host target linkage')
