from app.services.ticket_service import TicketService


def create_ticket(request_body: dict) -> dict:
    service = TicketService()
    ticket = service.create_ticket(request_body["title"], request_body.get("priority", "normal"))
    return {"id": ticket.id, "status": ticket.status}


def update_ticket_status(ticket_id: str, status: str) -> dict:
    service = TicketService()
    ticket = service.transition_status(ticket_id, status)
    return {"id": ticket.id, "status": ticket.status}
