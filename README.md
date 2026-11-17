# Online Job Portal

CareerConnect is a small web-based job portal written in Java. It uses the JDK's built-in HTTP server and has no external library dependencies.

## Features

- Register job-seeker profiles with skills, experience, and resume summaries
- Search vacancies by keyword, city, domain, experience, and salary
- View skill-match percentages and submit applications
- Track application statuses and simulate recruiter updates
- Publish new vacancies from the recruiter desk

## Requirements

- JDK 11 or newer

## Run

From the project directory, start the server:

```powershell
java WebJobPortal.java
```

Then open [http://localhost:8080](http://localhost:8080).

To use a different port, pass it as an argument:

```powershell
java WebJobPortal.java 8081
```

Alternatively, compile and run:

```powershell
javac WebJobPortal.java
java WebJobPortal
```

## Notes

This is a demo application. Its sample data and registered users are stored in memory and reset when the server stops. Do not use it to store real personal information or passwords.
