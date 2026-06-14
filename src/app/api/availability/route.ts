import { NextRequest, NextResponse } from "next/server";
import { isAvailable } from "@/lib/availability";

// GET /api/availability?unitId=&checkIn=&checkOut=
export async function GET(req: NextRequest) {
  const { searchParams } = new URL(req.url);
  const unitId = searchParams.get("unitId");
  const checkIn = searchParams.get("checkIn");
  const checkOut = searchParams.get("checkOut");

  if (!unitId || !checkIn || !checkOut) {
    return NextResponse.json({ error: "unitId, checkIn, checkOut erforderlich" }, { status: 400 });
  }

  const available = await isAvailable(unitId, new Date(checkIn), new Date(checkOut));
  return NextResponse.json({ available });
}
