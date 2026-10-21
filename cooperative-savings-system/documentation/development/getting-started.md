# Getting started (development)

## 1. Clone / open the repo

Open `cooperative-savings-system` in your IDE.

## 2. Environment

```bash
cp .env.example .env
```

Set `POSTGRES_*` and JWT secrets.

## 3. Database

Create `cooperative_savings_db` and user as described in the root README. Confirm:

```bash
psql -h localhost -U csams_user -d cooperative_savings_db -c "SELECT 1;"
```

## 4. Backend

```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Confirm Flyway in logs (`Successfully applied … migration`) and open Swagger UI.

## 5. Frontend

```bash
cd frontend
npm install
npm run dev
```

Open http://localhost:5173

### Default admin (local, change immediately)

- Username: `superadmin`
- Password: `ChangeMe@123!`

Created automatically when the database has no users.

## 6. Phase gate checklist

After each phase:

- [ ] `mvn test`
- [ ] `npm test`
- [ ] `npm run build`
- [ ] Flyway migrations clean on empty DB
- [ ] No TypeScript / lint blockers
- [ ] README / docs updated
