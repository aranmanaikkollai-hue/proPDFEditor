#!/bin/bash
# KAPT Fix Verification Script for proPDFEditor
# Run this after applying all fixes to verify configuration

echo "=========================================="
echo "KAPT Fix Verification"
echo "=========================================="
echo ""

# Check 1: Verify plugin IDs are unified
echo "[1/8] Checking kotlin-parcelize plugin ID consistency..."
CORE_PARCELIZE=$(grep -c "org.jetbrains.kotlin.plugin.parcelize" core/build.gradle 2>/dev/null || echo "0")
VIEWER_PARCELIZE=$(grep -c "org.jetbrains.kotlin.plugin.parcelize" viewer/build.gradle 2>/dev/null || echo "0")
APP_PARCELIZE=$(grep -c "org.jetbrains.kotlin.plugin.parcelize" app/build.gradle 2>/dev/null || echo "0")

if [ "$CORE_PARCELIZE" -gt 0 ] && [ "$VIEWER_PARCELIZE" -gt 0 ] && [ "$APP_PARCELIZE" -gt 0 ]; then
    echo "  ✓ All modules use unified 'org.jetbrains.kotlin.plugin.parcelize' plugin ID"
else
    echo "  ✗ Mismatch detected: core=$CORE_PARCELIZE, viewer=$VIEWER_PARCELIZE, app=$APP_PARCELIZE"
fi

# Check 2: Verify BouncyCastle resolution
echo "[2/8] Checking BouncyCastle dependency resolution..."
SECURITY_BC=$(grep -c "bcprov-jdk15to18" security/build.gradle 2>/dev/null || echo "0")
ROOT_BC=$(grep -c "bcprov-jdk15to18" build.gradle 2>/dev/null || echo "0")

if [ "$SECURITY_BC" -gt 0 ] && [ "$ROOT_BC" -gt 0 ]; then
    echo "  ✓ BouncyCastle unified to jdk15to18"
else
    echo "  ✗ BouncyCastle mismatch: security=$SECURITY_BC, root=$ROOT_BC"
fi

# Check 3: Verify KAPT workarounds in gradle.properties
echo "[3/8] Checking KAPT workarounds..."
for prop in "kapt.incremental.apt=false" "kapt.use.worker.api=false" "kapt.include.compile.classpath=false" "kapt.classloaders.cache.size=0"; do
    if grep -q "$prop" gradle.properties 2>/dev/null; then
        echo "  ✓ $prop"
    else
        echo "  ✗ Missing: $prop"
    fi
done

# Check 4: Verify app/build.gradle has all required dependencies
echo "[4/8] Checking app/build.gradle dependencies..."
for dep in "composeOptions" "room-runtime" "camera-core" "navigation-compose" "hilt-navigation-compose" "work-runtime"; do
    if grep -q "$dep" app/build.gradle 2>/dev/null; then
        echo "  ✓ $dep"
    else
        echo "  ✗ Missing: $dep"
    fi
done

# Check 5: Verify root build.gradle has projectsEvaluated block
echo "[5/8] Checking root build.gradle KAPT fix..."
if grep -q "gradle.projectsEvaluated" build.gradle 2>/dev/null; then
    echo "  ✓ gradle.projectsEvaluated block present"
else
    echo "  ✗ Missing gradle.projectsEvaluated block"
fi

# Check 6: Verify no legacy kotlin-parcelize IDs
echo "[6/8] Checking for legacy kotlin-parcelize plugin IDs..."
LEGACY_COUNT=$(grep -r "id 'kotlin-parcelize'" --include="*.gradle" . 2>/dev/null | grep -v "org.jetbrains.kotlin.plugin.parcelize" | wc -l)
if [ "$LEGACY_COUNT" -eq 0 ]; then
    echo "  ✓ No legacy 'kotlin-parcelize' IDs found"
else
    echo "  ✗ Found $LEGACY_COUNT legacy 'kotlin-parcelize' IDs"
    grep -r "id 'kotlin-parcelize'" --include="*.gradle" . 2>/dev/null | grep -v "org.jetbrains.kotlin.plugin.parcelize"
fi

# Check 7: Verify no direct legacy BouncyCastle dependency declarations
echo "[7/8] Checking for direct legacy BouncyCastle dependencies..."
CONFLICT_BC=$(rg "^[[:space:]]*(implementation|api|compileOnly|runtimeOnly|kapt|annotationProcessor)[[:space:]]+['\"][^'\"]*bcprov-jdk15on" --glob "*.gradle" . 2>/dev/null | wc -l)
if [ "$CONFLICT_BC" -eq 0 ]; then
    echo "  ✓ No direct bcprov-jdk15on dependency declarations found"
else
    echo "  ✗ Found $CONFLICT_BC direct bcprov-jdk15on dependency declarations"
    rg "^[[:space:]]*(implementation|api|compileOnly|runtimeOnly|kapt|annotationProcessor)[[:space:]]+['\"][^'\"]*bcprov-jdk15on" --glob "*.gradle" . 2>/dev/null
fi

# Check 8: Verify CircleCI config
echo "[8/8] Checking CircleCI config..."
if [ -f ".circleci/config.yml" ]; then
    if grep -q "Delete stale kotlin_module files" .circleci/config.yml 2>/dev/null; then
        echo "  ✓ CircleCI config has stale module cleanup"
    else
        echo "  ✗ CircleCI config missing stale module cleanup"
    fi
else
    echo "  ✗ .circleci/config.yml not found"
fi

echo ""
echo "=========================================="
echo "Verification Complete"
echo "=========================================="
echo ""
echo "Next steps:"
echo "  1. Run: ./gradlew clean --no-daemon"
echo "  2. Run: find . -path '*/build' -type d -exec rm -rf {} + 2>/dev/null || true"
echo "  3. Run: ./gradlew :app:assembleDebug --no-daemon --stacktrace"
echo ""
