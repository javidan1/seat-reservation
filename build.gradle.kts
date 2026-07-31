plugins {
	java
	id("org.springframework.boot") version "4.1.0"
	id("io.spring.dependency-management") version "1.1.7"
	id("com.diffplug.spotless") version "7.2.1"
}

group = "com.jmammadov"
version = "0.0.1-SNAPSHOT"
description = "Seat reservation service"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.flywaydb:flyway-database-postgresql")
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testImplementation("org.awaitility:awaitility")
	testImplementation("com.tngtech.archunit:archunit-junit5:1.4.2")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

spotless {
	java {
		googleJavaFormat()
		formatAnnotations()
	}
}

// Fast suite: everything except @Tag("load"). Runs in CI on every push.
tasks.test {
	useJUnitPlatform {
		excludeTags("load")
	}
}

// Full-size stress runs, kept out of the default build so CI stays fast.
// Usage: ./gradlew stressTest [-Pstress.threads=400]
tasks.register<Test>("stressTest") {
	description = "Runs the full-size concurrency stress suite (@Tag(\"load\"))."
	group = "verification"
	testClassesDirs = sourceSets.test.get().output.classesDirs
	classpath = sourceSets.test.get().runtimeClasspath
	useJUnitPlatform {
		includeTags("load")
	}
	systemProperty("stress.full", "true")
	findProperty("stress.threads")?.let { systemProperty("stress.threads", it) }
	shouldRunAfter(tasks.test)
}
