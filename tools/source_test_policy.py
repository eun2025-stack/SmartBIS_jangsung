"""Keep original-material checks out of collection correctness results."""
import unittest

def source_material_test(target):
    target.__source_material_dependency__=True
    return unittest.skip('SOURCE_MATERIAL_DEPENDENCY: 원자료 검증은 수집 판정 제외; 별도 적재 검증 필요')(target)
